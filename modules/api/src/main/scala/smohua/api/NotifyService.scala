package smohua.api

import sbirds.db.*
import sbirds.links.{StuiClient, StuiMessage}
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import smohua.core.*
import smohua.db.*
import sbirds.mail.{Mail, Mailer}
import sbirds.observe.{Log, Metrics, Telemetry}
import smohua.shared.*

import java.time.{Instant, ZoneId}
import scala.concurrent.duration.*

/**
 * 通知. 回答が届いた・受付を始めた・締め切ったときに知らせる.
 *  - フォームの持ち主と編集できる人へ（form.notify で選ぶ）: 画面内の通知とプッシュ通知（PWA）はその場で出す.
 *    メール（SMTP. 送り先を skereru にすれば skereru に届く）と stui（POST /api/apps/notify）は、送信待ちの表（notify_outbox）に積み、裏のワーカーが送る.
 *    受付の開始・締め切りは statusEvents を選んだときだけ.
 *  - 回答した人へ（receipt）: 回答の控えをメールで送る（ログインして回答した人はそのアドレス、匿名なら「メールアドレス」の質問の答え）.
 *  - フォームの通知先へ（受信 Webhook・Webhook・メールアドレス）: TargetService が送る.
 * 複数ノードは SKIP LOCKED で送信待ちを取り合う. 失敗したら間を空けて送り直す.
 */
final class NotifyService(forms: FormStore,
                          users: UserStore,
                          targets: TargetService,
                          stui: Option[StuiClient],
                          mailer: Option[Mailer],
                          push: Option[sbirds.push.PushService],
                          cfg: ApiConfig) {

  private val MailProvider = "mail"
  private val StuiProvider = "stui"

  /** 同じアドレスへの回答の控えは、1時間にこの数まで（匿名のフォームから知らない人へ送り付けられないように）. */
  private val ReceiptsPerHour = 5

  private val zone: ZoneId = scala.util.Try(ZoneId.of(cfg.timezone)).getOrElse(ZoneId.of("Asia/Tokyo"))

  /** 回答が届いた. who は回答した人（ログインして回答したときだけ）. */
  def responseArrived(f: FormRow, r: ResponseRow, who: Option[User]): IO[Unit] = {
    val payload = FormPayload.response(f, r, who.map(u => (u.name, u.email)), cfg.publicUrl)
    val body = (who.map(w => s"回答者: ${w.name}").toList ++ List(FormPayload.digest(f, r))).filter(
      _.nonEmpty
    ).mkString("\n")
    Telemetry.span("notify.fanout", "form.id" -> f.id.toString)(
      toWatchers(f, "response", s"「${f.title}」に回答が届きました", body, Paths.responses(f.id), r.userId) *>
        receipt(f, r, who) *> targets.enqueue(f, payload)
    ).handleErrorWith(e => Log.report(ErrorCode.NotifyEnqueueFailed, e.toString, e))
  }

  /** 受付を始めた（accepting = true）・締め切った. */
  def statusChanged(f: FormRow, accepting: Boolean): IO[Unit] =
    forms.stats(f.id).flatMap { (count, _) =>
      val now = Instant.now()
      val payload = FormPayload.status(f, accepting, count, now, cfg.publicUrl)
      val t = FormPayload.text(payload, zone)
      IO.whenA(f.notice.statusEvents)(toWatchers(
        f,
        "status",
        t.title,
        t.lines.mkString("\n"),
        if accepting then Paths.fill(f.id) else Paths.responses(f.id),
        None
      )) *> targets.enqueue(f, payload)
    }.handleErrorWith(e => Log.report(ErrorCode.NotifyEnqueueFailed, e.toString, e))

  /**
   * いま回答を受け付けているかを見比べ、変わったフォームについて知らせる. id を渡せばそのフォームだけ（状態を変えた直後）、
   * なければすべて（受付期間の始まり・終わりは時刻で変わるので、裏で定期的に見る）.
   */
  def syncAccepting(id: Option[java.util.UUID]): IO[Unit] =
    forms.syncAccepting(id).flatMap(_.traverse_((f, accepting) => statusChanged(f, accepting)))

  def acceptingLoop: IO[Nothing] =
    Periodic.run(30.seconds, 10.seconds, ErrorCode.AcceptingWatchFailed)(syncAccepting(None))

  /** 持ち主と編集できる人へ（except は知らせない人. 自分で回答した人）. */
  private def toWatchers(f: FormRow,
                         kind: String,
                         title: String,
                         body: String,
                         link: String,
                         except: Option[java.util.UUID]): IO[Unit] = {
    val n = f.notice
    val url = s"${cfg.publicUrl}$link"
    forms.watchers(f.id).flatMap(ws =>
      ws.toList.filterNot(except.contains).traverse_ { uid =>
        IO.whenA(n.inApp)(forms.notify(uid, kind, Some(f.id), title, body.take(500), link).void *>
          push.traverse_(_.send(
            uid,
            sbirds.push.PushMessage(title, body.take(300), link, Some(s"form:${f.id}"))
          ).start.void)) *>
          IO.whenA(n.mail && cfg.mail)(forms.enqueue(
            uid,
            MailProvider,
            Json.obj(
              "subject" -> s"[smohua] $title".asJson,
              "body" -> List(title, "", body.take(2000), "", url, "", "-- ", "smohua（通知はフォームの「設定」で変えられます）")
                .mkString("\n").asJson
            )
          )) *>
          // stui: チャンネルに出すなら持ち主の連携で1回だけ、DM なら連携している人それぞれに.
          // title は stui がエスケープするのでそのまま、本文（回答者の書いた文を含む）は記号を無害にして送る
          IO.whenA(n.stui && stui.isDefined && (n.stuiChannel.trim.isEmpty || uid == f.ownerId))(forms.enqueue(
            uid,
            StuiProvider,
            Json.obj(
              "title" -> title.asJson,
              "text" -> body.take(2000).linesIterator.map(FormPayload.plain).mkString("\n").asJson,
              "url" -> url.asJson,
              "channel" -> Option(n.stuiChannel.trim).filter(_.nonEmpty).asJson
            ).dropNullValues
          ))
      }
    )
  }

  /** 回答の控え. ログインして回答した人は IdP のアドレス（なければ質問の答え）、匿名なら「メールアドレス」の質問の答えへ. */
  private def receipt(f: FormRow, r: ResponseRow, who: Option[User]): IO[Unit] = {
    val answers = r.answers.as[Map[String, Json]].getOrElse(Map.empty)
    val to = who.flatMap(_.email).filter(Forms.isEmail).orElse(Forms.receiptAddress(f.questions, answers))
    (f.notice.receipt && cfg.mail, to) match {
      case (true, Some(a)) =>
        val (subject, body) = FormPayload.receipt(f, answers, "smohua")
        forms.enqueueMail(a, Json.obj("subject" -> subject.asJson, "body" -> body.asJson), ReceiptsPerHour).flatMap(
          ok =>
            Metrics.count("smohua.receipts", "result" -> (if ok then "queued" else "limited"))
        )
      case _ => IO.unit
    }
  }

  private def sendMail(row: OutboxRow, m: Mailer): IO[OutboxResult] = {
    val c = row.payload.hcursor
    def send(to: String, name: String) =
      Telemetry.span("mail.send")(m.send(Mail(
        to,
        name,
        c.get[String]("subject").getOrElse("[smohua]"),
        c.get[String]("body").getOrElse("")
      ))).as(OutboxResult.Sent)
    (row.userId, row.address) match {
      case (_, Some(a)) => send(a, "")
      case (Some(uid), None) =>
        users.find(uid).flatMap {
          case Some(u) if !u.disabled && u.email.exists(_.contains("@")) => send(u.email.get, u.name)
          case _ => IO.pure(OutboxResult.Skip("宛先のユーザーが無効か、メールアドレスがありません"))
        }
      case _ => IO.pure(OutboxResult.Skip("宛先がありません"))
    }
  }

  /** stui へ通知する（本人の連携のトークンで）. 401 なら連携が切れているので外す（StuiClient）. */
  private def sendStui(row: OutboxRow, c: StuiClient): IO[OutboxResult] =
    row.userId match {
      case None => IO.pure(OutboxResult.Skip("宛先がありません"))
      case Some(uid) =>
        val h = row.payload.hcursor
        val m = StuiMessage(
          h.get[String]("title").getOrElse(""),
          h.get[String]("text").getOrElse(""),
          h.get[String]("url").getOrElse("")
        )
        Telemetry.span("stui.notify")(c.notify(uid, m, h.get[String]("channel").toOption)).map(_._1)
    }

  /** 送信待ちの通知を送るワーカー（全ノードで動かし、SKIP LOCKED で取り合う）. */
  def outboxLoop: IO[Nothing] = Periodic.run(10.seconds, 5.seconds, ErrorCode.NotifyWorkerFailed)(sendDue.void)

  /** 送る時刻が来た送信待ちを1回分送る（送った数）. */
  def sendDue: IO[Int] =
    forms.processOutbox(20) { row =>
      val sent: IO[OutboxResult] = (row.provider, mailer, stui) match {
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
