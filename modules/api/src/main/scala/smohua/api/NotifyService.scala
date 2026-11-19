package smohua.api

import sbirds.links.IntegrationTokenStore
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import smohua.auth.SecretBox
import smohua.core.*
import smohua.db.*
import sbirds.mail.{Mail, Mailer}
import sbirds.observe.{Log, Metrics, Telemetry}
import smohua.shared.*

import scala.concurrent.duration.*

/**
 * 通知。回答が届いたら、フォームの持ち主と編集できる人に知らせる。
 *  - 画面内の通知とプッシュ通知（PWA）は、その場で出す。
 *  - メール（SMTP。送り先を skereru にすれば skereru に届く）と stui（POST /api/apps/notify）は、送信待ちの表（notify_outbox）に積み、
 *    裏のワーカーが送る（複数ノードは SKIP LOCKED で取り合う。失敗したら間を空けて送り直す）。
 */
final class NotifyService(forms: FormStore,
                          users: UserStore,
                          tokens: IntegrationTokenStore,
                          box: SecretBox,
                          http: Client[IO],
                          mailer: Option[Mailer],
                          push: Option[sbirds.push.PushService],
                          cfg: ApiConfig) {

  private val MailProvider = "mail"
  private val StuiProvider = "stui"

  /** 回答が届いた。who は回答した人の名前（ログインしていなければ None）。 */
  def responseArrived(f: FormRow, r: ResponseRow, who: Option[String]): IO[Unit] = {
    val n = f.notice
    val title = s"「${f.title}」に回答が届きました"
    val body =
      (who.map(w => s"回答者: $w").toList ++ List(Forms.digest(
        f.questions,
        r.answers.as[Map[String, Json]].getOrElse(Map.empty)
      ))).filter(_.nonEmpty).mkString("\n")
    val link = Paths.responses(f.id)
    val url = s"${cfg.publicUrl}$link"
    Telemetry.span("notify.fanout", "form.id" -> f.id.toString)(forms.watchers(f.id).flatMap { ws =>
      val to = ws.toList.filterNot(r.userId.contains) // 自分で回答した人には知らせない
      to.traverse_ { uid =>
        IO.whenA(n.inApp)(forms.notify(uid, "response", Some(f.id), title, body.take(500), link).void *>
          push.traverse_(_.send(
            uid,
            sbirds.push.PushMessage(title, body.take(300), link, Some(s"form:${f.id}"))
          ).start.void)) *>
          IO.whenA(n.mail && cfg.mail)(forms.enqueue(
            uid,
            MailProvider,
            Json.obj(
              "subject" -> s"[smohua] $title".asJson,
              "body" -> List(
                title,
                "",
                body.take(2000),
                "",
                url,
                "",
                "-- ",
                "smohua（通知はフォームの「設定」で変えられます）"
              ).mkString("\n").asJson
            )
          )) *>
          // stui: チャンネルに出すなら持ち主の連携で1回だけ、DM なら連携している人それぞれに
          IO.whenA(n.stui && cfg.stui.isDefined && (n.stuiChannel.trim.isEmpty || uid == f.ownerId))(forms.enqueue(
            uid,
            StuiProvider,
            Json.obj(
              "title" -> title.asJson,
              "text" -> body.take(2000).asJson,
              "url" -> url.asJson,
              "channel" -> Option(n.stuiChannel.trim).filter(_.nonEmpty).asJson
            ).dropNullValues
          ))
      }
    }).handleErrorWith(e => Log.report(ErrorCode.NotifyEnqueueFailed, e.toString, e))
  }

  private def sendMail(row: OutboxRow, m: Mailer): IO[OutboxResult] =
    users.find(row.userId).flatMap {
      case Some(u) if !u.disabled && u.email.exists(_.contains("@")) =>
        val c = row.payload.hcursor
        Telemetry.span("mail.send")(m.send(Mail(
          u.email.get,
          u.name,
          c.get[String]("subject").getOrElse("[smohua]"),
          c.get[String]("body").getOrElse("")
        )))
          .as(OutboxResult.Sent)
      case _ => IO.pure(OutboxResult.Skip("宛先のユーザーが無効か、メールアドレスがありません"))
    }

  /** stui へ通知する（本人の連携のトークンで）。401 なら連携が切れているので外す。 */
  private def sendStui(row: OutboxRow, c: LinkService): IO[OutboxResult] =
    tokens.token(row.userId, StuiProvider).map(_.flatMap(box.decrypt)).flatMap {
      case None => IO.pure(OutboxResult.Skip("stui と連携していません"))
      case Some(token) =>
        val req = Request[IO](Method.POST, Uri.unsafeFromString(s"${c.apiUrl}/api/apps/notify")).withEntity(row.payload)
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
        Telemetry.span("stui.notify")(http.run(req).use { r =>
          if r.status.isSuccess then IO.pure(OutboxResult.Sent)
          else if r.status == Status.Unauthorized then
            tokens.deleteToken(row.userId, StuiProvider).as(OutboxResult.Skip("stui との連携が切れています"))
          else if r.status.code >= 400 && r.status.code < 500 then
            IO.pure(OutboxResult.Skip(s"stui が ${r.status.code} を返しました"))
          else IO.pure(OutboxResult.Retry(s"stui が ${r.status.code} を返しました"))
        }.timeout(10.seconds))
    }

  /** 送信待ちの通知を送るワーカー（全ノードで動かし、SKIP LOCKED で取り合う）。 */
  def outboxLoop: IO[Nothing] =
    Periodic.run(10.seconds, 5.seconds, ErrorCode.NotifyWorkerFailed) {
      forms.processOutbox(20) { row =>
        val sent: IO[OutboxResult] = (row.provider, mailer, cfg.stui) match {
          case (MailProvider, Some(m), _) => sendMail(row, m)
          case (MailProvider, None, _) => IO.pure(OutboxResult.Skip("SMTP が設定されていません"))
          case (StuiProvider, _, Some(c)) => sendStui(row, c)
          case _ => IO.pure(OutboxResult.Skip("連携が設定されていません"))
        }
        sent.handleError(e => OutboxResult.Retry(String.valueOf(e.getMessage).take(300))).flatTap { res =>
          val result = res match {
            case OutboxResult.Sent => "ok"
            case OutboxResult.Skip(_) => "skipped"
            case OutboxResult.Retry(_) => "error"
          }
          Metrics.count("smohua.notifications.sent", "provider" -> row.provider, "result" -> result)
        }
      }
    }
}
