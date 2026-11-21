package smohua.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import org.http4s.client.Client
import sbirds.auth.Crypto
import sbirds.db.{OutboxResult, Periodic}
import sbirds.links.{StuiClient, Webhook}
import sbirds.mail.{Mail, Mailer}
import sbirds.net.NetGuard
import sbirds.observe.{Metrics, Telemetry}
import sbirds.shared.AppError
import smohua.auth.SecretBox
import smohua.core.{ErrorCode, Forms}
import smohua.db.*
import smohua.shared.*

import java.time.{Instant, ZoneId}
import java.util.UUID
import scala.concurrent.duration.*

/**
 * フォームの通知先（持ち主と管理者が決める. 回答の中身がフォームの外へ出るため）.
 *  - chat_hook: stui・Slack・Mattermost の受信 Webhook に {"text", "username"} を投稿する（URL が鍵）.
 *  - webhook: イベントの JSON（FormPayload）を、HMAC-SHA256 の署名（X-Smohua-Signature）付きで送る.
 *  - mail: 決まったメールアドレスへ送る（SMTP）.
 * 送信は送信待ちの表（form_delivery）に積み、裏のワーカーが送る（複数ノードは SKIP LOCKED で取り合う. 失敗したら間を空けて送り直す）.
 * 利用者が決めた URL へは hookHttp（NetGuard.client）で送り、内部のアドレスは policy（NOTIFY_ALLOW_INTERNAL）で許したものだけにする.
 */
final class TargetService(store: TargetStore,
                          forms: FormStore,
                          hookHttp: Client[IO],
                          policy: NetGuard.Policy,
                          mailer: Option[Mailer],
                          box: SecretBox,
                          cfg: ApiConfig) {

  private val AppName = "smohua"

  private val zone: ZoneId = scala.util.Try(ZoneId.of(cfg.timezone)).getOrElse(ZoneId.of("Asia/Tokyo"))

  private def newSecret(): String = box.encrypt(Crypto.randomToken(24))

  def list(f: FormRow): IO[List[FormTargetDto]] = store.targets(f.id).map(_.map(dto))

  def create(f: FormRow, r: FormTargetReq): Res[FormTargetDto] =
    store.count(f.id).flatMap { n =>
      if n >= store.MaxTargets then IO.pure(Left(AppError(ErrorCode.FormTargetTooMany)))
      else
        validate(r).flatMap(_.traverse { v =>
          val row = FormTargetRow(
            UUID.randomUUID(),
            f.id,
            v.name,
            v.kind,
            v.url,
            v.address,
            Option.when(v.kind == TargetKind.Webhook)(newSecret()),
            v.events,
            v.enabled,
            None,
            Instant.now()
          )
          store.insert(row).as(dto(row))
        })
    }

  /** 種類は変えられない（変えるときは作り直す）. */
  def update(f: FormRow, id: UUID, r: FormTargetReq): Res[FormTargetDto] =
    store.target(f.id, id).flatMap {
      case None => IO.pure(Left(AppError(ErrorCode.FormTargetNotFound)))
      case Some(t) =>
        validate(r.copy(kind = t.kind)).flatMap(_.flatTraverse(v =>
          store.update(f.id, id, v) *>
            store.target(f.id, id).map(_.map(dto).toRight(AppError(ErrorCode.FormTargetNotFound)))
        ))
    }

  def delete(f: FormRow, id: UUID): Res[Unit] =
    store.delete(f.id, id).map(ok => Either.cond(ok, (), AppError(ErrorCode.FormTargetNotFound)))

  /** 試しに送る（送信待ちを通さず、その場で送って結果を返す）. */
  def test(f: FormRow, id: UUID, userName: String): Res[Unit] =
    store.target(f.id, id).flatMap {
      case None => IO.pure(Left(AppError(ErrorCode.FormTargetNotFound)))
      case Some(t) =>
        send(t, UUID.randomUUID(), FormPayload.test(f, userName, cfg.publicUrl)).map {
          case (OutboxResult.Sent, _) => Right(())
          case (OutboxResult.Skip(m), _) => Left(AppError(ErrorCode.NotifyTestFailed, Some(m)))
          case (OutboxResult.Retry(m), _) => Left(AppError(ErrorCode.NotifyTestFailed, Some(m)))
        }
    }

  /** イベントを、それを選んだ通知先の送信待ちに積む. */
  def enqueue(f: FormRow, payload: Json): IO[Unit] =
    store.enqueueEvent(f.id, FormPayload.event(payload), payload).void

  private def send(t: FormTargetRow, deliveryId: UUID, payload: Json): IO[(OutboxResult, Option[Int])] =
    t.kind match {
      case TargetKind.ChatHook =>
        t.url match {
          case None => IO.pure((OutboxResult.Skip("URL がありません"), None))
          case Some(u) =>
            NetGuard.check(u, policy).flatMap {
              case Left(r) => IO.pure((OutboxResult.Skip(r.message), None))
              case Right(_) => StuiClient.postHook(hookHttp, u, FormPayload.chat(payload, zone), AppName)
            }
        }
      case TargetKind.Webhook =>
        (t.url, t.secretEnc.flatMap(box.decrypt)) match {
          case (Some(u), Some(sec)) =>
            val body = payload.deepMerge(Json.obj("deliveryId" -> Json.fromString(deliveryId.toString))).noSpaces
            Webhook.post(
              hookHttp,
              policy,
              "Smohua",
              Webhook.Send(u, FormPayload.event(payload), deliveryId.toString, body, sec)
            )
          case _ => IO.pure((OutboxResult.Skip("URL か署名の鍵がありません"), None))
        }
      case TargetKind.Mail =>
        (t.address, mailer) match {
          case (_, None) => IO.pure((OutboxResult.Skip("SMTP が設定されていません"), None))
          case (None, _) => IO.pure((OutboxResult.Skip("宛先がありません"), None))
          case (Some(a), Some(m)) =>
            val (subject, body) = FormPayload.mail(payload, zone, AppName)
            Telemetry.span("mail.send")(m.send(Mail(a, t.name, subject, body))).as((OutboxResult.Sent, None))
        }
      case k => IO.pure((OutboxResult.Skip(s"知らない種類です: $k"), None))
    }

  /** 入力の確認（名前・送り先・きっかけ）. URL は送れる宛先か（内部のアドレスでないか）も確かめる. */
  private def validate(r: FormTargetReq): Res[FormTargetReq] = {
    val name = r.name.trim.take(100)
    val url = r.url.map(_.trim).filter(_.nonEmpty)
    val events = r.events.distinct.filter(FormEvent.all.contains)
    def bad(m: String) = IO.pure(Left(AppError(ErrorCode.FormTargetInvalid, Some(m))))
    if name.isEmpty then bad("名前を入力してください")
    else if events.isEmpty then bad("知らせるきっかけを1つ以上選んでください")
    else
      r.kind match {
        case TargetKind.Mail =>
          r.address.map(_.trim).filter(_.nonEmpty) match {
            case _ if mailer.isEmpty => IO.pure(Left(AppError(ErrorCode.MailNotConfigured)))
            case Some(a) if Forms.isEmail(a) =>
              IO.pure(Right(FormTargetReq(name, r.kind, None, Some(a), events, r.enabled)))
            case _ => bad("メールアドレスの形で入力してください")
          }
        case TargetKind.ChatHook | TargetKind.Webhook =>
          url match {
            case None => bad("送り先の URL を入力してください")
            case Some(u) if u.length > 2000 => bad("URL が長すぎます")
            case Some(u) if !(u.startsWith("https://") || u.startsWith("http://")) => bad("URL は https:// で始めてください")
            case Some(u) =>
              NetGuard.check(u, policy).map {
                case Left(e) => Left(AppError(ErrorCode.NotifyUrlRejected, Some(e.message)))
                case Right(_) => Right(FormTargetReq(name, r.kind, Some(u), None, events, r.enabled))
              }
          }
        case _ => bad("通知先の種類が正しくありません")
      }
  }

  private def dto(t: FormTargetRow): FormTargetDto =
    FormTargetDto(
      t.id,
      t.name,
      t.kind,
      t.url,
      t.address,
      t.secretEnc.flatMap(box.decrypt),
      t.events,
      t.enabled,
      t.lastError,
      t.createdAt
    )

  /** 送信待ちを送るワーカー（どのノードで動かしてもよい）. */
  def loop: IO[Nothing] = Periodic.run(10.seconds, 5.seconds, ErrorCode.DeliveryWorkerFailed)(deliverDue.void)

  /** 送る時刻が来た送信待ちを1回分送る（送った数）. */
  def deliverDue: IO[Int] =
    store.processDeliveries(50)(d =>
      Telemetry.span("notify.deliver", "notify.kind" -> d.target.kind)(send(d.target, d.id, d.payload)).flatTap {
        (r, _) =>
          val result = r match {
            case OutboxResult.Sent => "ok"
            case OutboxResult.Skip(_) => "skipped"
            case OutboxResult.Retry(_) => "error"
          }
          Metrics.count("smohua.notifications.sent", "provider" -> d.target.kind, "result" -> result)
      }
    )
}
