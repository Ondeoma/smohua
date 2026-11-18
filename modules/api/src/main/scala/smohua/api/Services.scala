package smohua.api

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
import smohua.notify.{Mail, Mailer}
import smohua.observe.{Log, Metrics, Telemetry}
import smohua.shared.*

import java.util.UUID
import scala.concurrent.duration.*

/**
 * 通知。回答が届いたら、フォームの持ち主と編集できる人に知らせる。
 *  - 画面内の通知とプッシュ通知（PWA）は、その場で出す。
 *  - メール（SMTP。送り先を skereru にすれば skereru に届く）と stui（POST /api/apps/notify）は、送信待ちの表（notify_outbox）に積み、
 *    裏のワーカーが送る（複数ノードは SKIP LOCKED で取り合う。失敗したら間を空けて送り直す）。
 */
final class NotifyService(forms: FormStore, users: UserStore, tokens: IntegrationTokenStore, box: SecretBox, http: Client[IO],
    mailer: Option[Mailer], push: Option[smohua.push.PushService], cfg: ApiConfig):

  private val MailProvider = "mail"
  private val StuiProvider = "stui"

  /** 回答が届いた。who は回答した人の名前（ログインしていなければ None）。 */
  def responseArrived(f: FormRow, r: ResponseRow, who: Option[String]): IO[Unit] =
    val n     = f.notice
    val title = s"「${f.title}」に回答が届きました"
    val body  = (who.map(w => s"回答者: $w").toList ++ List(Forms.digest(f.questions, r.answers.as[Map[String, Json]].getOrElse(Map.empty)))).filter(_.nonEmpty).mkString("\n")
    val link  = Paths.responses(f.id)
    val url   = s"${cfg.publicUrl}$link"
    Telemetry.span("notify.fanout", "form.id" -> f.id.toString)(forms.watchers(f.id).flatMap { ws =>
      val to = ws.toList.filterNot(r.userId.contains) // 自分で回答した人には知らせない
      to.traverse_ { uid =>
        IO.whenA(n.inApp)(forms.notify(uid, "response", Some(f.id), title, body.take(500), link).void *>
          push.traverse_(_.send(uid, smohua.push.PushMessage(title, body.take(300), link, Some(s"form:${f.id}"))).start.void)) *>
          IO.whenA(n.mail && cfg.mail)(forms.enqueue(uid, MailProvider, Json.obj("subject" -> s"[smohua] $title".asJson,
            "body" -> List(title, "", body.take(2000), "", url, "", "-- ", "smohua（通知はフォームの「設定」で変えられます）").mkString("\n").asJson))) *>
          // stui: チャンネルに出すなら持ち主の連携で1回だけ、DM なら連携している人それぞれに
          IO.whenA(n.stui && cfg.stui.isDefined && (n.stuiChannel.trim.isEmpty || uid == f.ownerId))(forms.enqueue(uid, StuiProvider, Json.obj(
            "title" -> title.asJson, "text" -> body.take(2000).asJson, "url" -> url.asJson,
            "channel" -> Option(n.stuiChannel.trim).filter(_.nonEmpty).asJson).dropNullValues))
      }
    }).handleErrorWith(e => Log.println(s"[smohua] notify failed: $e"))

  private def sendMail(row: OutboxRow, m: Mailer): IO[OutboxResult] =
    users.find(row.userId).flatMap {
      case Some(u) if !u.disabled && u.email.exists(_.contains("@")) =>
        val c = row.payload.hcursor
        Telemetry.span("mail.send")(m.send(Mail(u.email.get, u.name, c.get[String]("subject").getOrElse("[smohua]"), c.get[String]("body").getOrElse(""))))
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
          else if r.status == Status.Unauthorized then tokens.deleteToken(row.userId, StuiProvider).as(OutboxResult.Skip("stui との連携が切れています"))
          else if r.status.code >= 400 && r.status.code < 500 then IO.pure(OutboxResult.Skip(s"stui が ${r.status.code} を返しました"))
          else IO.pure(OutboxResult.Retry(s"stui が ${r.status.code} を返しました"))
        }.timeout(10.seconds))
    }

  /** 送信待ちの通知を送るワーカー（全ノードで動かし、SKIP LOCKED で取り合う）。 */
  def outboxLoop: IO[Nothing] =
    Periodic.run(10.seconds, 5.seconds)(e => s"[smohua] notify worker failed: $e") {
      forms.processOutbox(20) { row =>
        val sent: IO[OutboxResult] = (row.provider, mailer, cfg.stui) match
          case (MailProvider, Some(m), _) => sendMail(row, m)
          case (MailProvider, None, _)    => IO.pure(OutboxResult.Skip("SMTP が設定されていません"))
          case (StuiProvider, _, Some(c)) => sendStui(row, c)
          case _                   => IO.pure(OutboxResult.Skip("連携が設定されていません"))
        sent.handleError(e => OutboxResult.Retry(String.valueOf(e.getMessage).take(300))).flatTap { res =>
          val result = res match
            case OutboxResult.Sent     => "ok"
            case OutboxResult.Skip(_)  => "skipped"
            case OutboxResult.Retry(_) => "error"
          Metrics.count("smohua.notifications.sent", "provider" -> row.provider, "result" -> result)
        }
      }
    }

/** フォームの操作（REST・MCP・連携サービスで共通）。権限の確認・保存・監査ログ・通知まで行う。 */
final class FormService(forms: FormStore, notifier: NotifyService, activity: ActivityStore, cfg: ApiConfig):

  private val NotFound = Fail.NotFound("フォームが見つかりません")
  private val SummaryLimit = 5000

  /** 持ち主・編集できる人・管理者だけが見られる（それ以外には存在を見せない）。write は変更（参照専用のキーは不可）。 */
  def access(s: AuthSession, id: UUID, write: Boolean = false): Res[FormRow] =
    forms.find(id).flatMap {
      case None => IO.pure(Left(NotFound))
      case Some(f) =>
        visible(s, f).map {
          case false                        => Left(NotFound)
          case true if write && !s.canWrite => Left(Fail.Forbidden("このAPIキーは参照専用です"))
          case true                         => Right(f)
        }
    }

  /** 持ち主・管理者・編集できる人か。 */
  private def visible(s: AuthSession, f: FormRow): IO[Boolean] =
    if f.ownerId == s.user.id || s.canAdmin then IO.pure(true) else forms.isEditor(f.id, s.user.id)

  def dto(s: AuthSession, f: FormRow): IO[FormDto] =
    (forms.editors(f.id), forms.stats(f.id), forms.briefs(List(f.ownerId))).mapN { (es, st, owner) =>
      FormDto(f.id, f.title, f.description, f.status, f.access, f.oneResponse, f.confirmation, f.questions, f.notice,
        f.schedule, f.appearance, owner.headOption.getOrElse(UserBrief(f.ownerId, "", None)), es, st._1, st._2, f.createdAt, f.updatedAt, canEdit = s.canWrite)
    }

  def create(s: AuthSession, r: CreateFormReq): Res[FormDto] =
    (Forms.title(r.title), Forms.normalize(r.questions.getOrElse(Nil))).tupled match
      case Left(m) => IO.pure(Left(Fail.Invalid(m)))
      case Right((title, qs)) =>
        Telemetry.span("form.create")(forms.create(s.user.id, title, r.description.getOrElse("").trim.take(5000), qs)).flatMap { f =>
          Metrics.count("smohua.forms.created", "via" -> s.viaString) *>
            activity.audit(Some(s.user.id), s.viaString, "form.create", Some(f.id.toString), Json.obj("title" -> title.asJson)) *>
            dto(s, f).map(Right(_))
        }

  def update(s: AuthSession, id: UUID, r: UpdateFormReq): Res[FormDto] =
    access(s, id, write = true).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(f) =>
        val status  = r.status.getOrElse(f.status)
        val acc     = r.access.getOrElse(f.access)
        val checked: Either[String, (Option[String], Option[List[QuestionDto]], Option[FormScheduleDto], Option[FormAppearanceDto])] =
          for
            title <- r.title.traverse(Forms.title)
            qs    <- r.questions.traverse(Forms.normalize)
            sch   <- r.schedule.traverse(Forms.schedule)
            look  <- r.appearance.traverse(Forms.appearance)
            _     <- Either.cond(FormStatus.all.contains(status), (), s"status は ${FormStatus.all.mkString("・")} のどれかです")
            _     <- Either.cond(FormAccess.all.contains(acc), (), s"access は ${FormAccess.all.mkString("・")} のどれかです")
            _     <- Either.cond(!(status == FormStatus.Open && qs.getOrElse(f.questions).isEmpty), (), "質問を1つ以上作ってから公開してください")
            _     <- Either.cond(!(r.oneResponse.getOrElse(f.oneResponse) && acc == FormAccess.Anyone), (), "「1人1回」は、ログインした人だけが回答できるフォームで使えます")
            _     <- Either.cond(r.editorIds.forall(_.size <= 50), (), "編集できる人は50人までです")
            _     <- Either.cond(r.editorIds.isEmpty || f.ownerId == s.user.id || s.canAdmin, (), "編集できる人を変えられるのは、持ち主と管理者だけです")
            // チャンネルへの知らせは持ち主の stui の連携で送るので、送り先は持ち主（と管理者）だけが決める
            _     <- Either.cond(r.notice.forall(n => channel(n) == channel(f.notice)) || f.ownerId == s.user.id || s.canAdmin, (),
                       "stui のチャンネルを変えられるのは、持ち主と管理者だけです")
          yield (title, qs, sch, look)
        checked match
          case Left(m) => IO.pure(Left(Fail.Invalid(m)))
          case Right((title, qs, sch, look)) =>
            forms.update(id, title, r.description.map(_.trim.take(5000)), r.status, r.access, r.oneResponse, r.confirmation.map(_.trim.take(2000)), qs,
              r.notice.map(n => n.copy(stuiChannel = channel(n))), r.editorIds, sch, look).flatMap {
              case None => IO.pure(Left(NotFound))
              case Some(g) =>
                IO.whenA(r.status.exists(_ != f.status))(Metrics.count("smohua.forms.status_changed", "status" -> status) *>
                  activity.audit(Some(s.user.id), s.viaString, "form.status", Some(id.toString), Json.obj("status" -> status.asJson))) *>
                  dto(s, g).map(Right(_))
            }
    }

  private def channel(n: FormNotifyDto): String = n.stuiChannel.trim.take(100)

  def delete(s: AuthSession, id: UUID): Res[Unit] =
    access(s, id, write = true).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(f) if f.ownerId != s.user.id && !s.canAdmin => IO.pure(Left(Fail.Forbidden("削除できるのは、持ち主と管理者だけです")))
      case Right(f) =>
        forms.delete(id) *> activity.audit(Some(s.user.id), s.viaString, "form.delete", Some(id.toString), Json.obj("title" -> f.title.asJson)).as(Right(()))
    }

  /** 質問と設定を写した新しいフォーム（下書き。回答は写さない）。 */
  def duplicate(s: AuthSession, id: UUID): Res[FormDto] =
    access(s, id, write = true).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(f) =>
        forms.create(s.user.id, (f.title + " のコピー").take(200), f.description, f.questions).flatMap(n =>
          forms.update(n.id, None, None, None, Some(f.access), Some(f.oneResponse), Some(f.confirmation), None, Some(f.notice), None,
            appearance = Some(f.appearance))
            .flatMap(g => dto(s, g.getOrElse(n)).map(Right(_))))
    }

  // ---------------------------------------------------------------- 回答する側

  /** 回答する人に見せるフォーム。下書きは、編集できる人だけが見られる（プレビュー）。 */
  def public(s: Option[AuthSession], id: UUID): Res[PublicFormDto] =
    forms.find(id).flatMap {
      case None => IO.pure(Left(NotFound))
      case Some(f) =>
        val editor = s.fold(IO.pure(false))(visible(_, f))
        val done   = s.filter(_ => f.oneResponse).fold(IO.pure(false))(x => forms.answered(id, x.user.id))
        (editor, done).mapN { (ed, answered) =>
          if f.status == FormStatus.Draft && !ed then Left(NotFound)
          else
            val needLogin = f.access == FormAccess.Login && s.isEmpty
            // ログインが要るフォームは、ログインするまで質問を見せない
            Right(PublicFormDto(f.id, f.title, if needLogin then "" else f.description, f.status, f.access, f.oneResponse,
              if needLogin then Nil else f.questions, answered, needLogin, f.phase(java.time.Instant.now()), f.schedule, f.appearance))
        }
    }

  def submit(s: Option[AuthSession], id: UUID, r: SubmitReq): Res[SubmittedDto] =
    Telemetry.span("form.submit", "form.id" -> id.toString)(forms.find(id).flatMap {
      case None => IO.pure(Left(NotFound))
      case Some(f) if !f.isOpen => IO.pure(Left(f.phase(java.time.Instant.now()) match
        case FormPhase.Draft  => NotFound
        case FormPhase.Before => Fail.Conflict("このフォームはまだ回答を受け付けていません")
        case _                => Fail.Conflict("このフォームは回答を締め切りました")))
      case Some(f) if f.access == FormAccess.Login && s.isEmpty => IO.pure(Left(Fail.Unauthorized("回答するにはログインしてください")))
      case Some(_) if s.exists(!_.canWrite) => IO.pure(Left(Fail.Forbidden("このAPIキーは参照専用です")))
      case Some(f) =>
        Forms.validate(f.questions, r.answers) match
          case Left(errs) => IO.pure(Left(Fail.Invalid(errs.mkString("\n"))))
          case Right(answers) =>
            // URL を知っている人が答えるフォームは匿名（ログインしていても、だれが答えたかは記録しない）
            val who = s.filter(_ => f.access == FormAccess.Login)
            forms.submit(id, who.map(_.user.id), answers).orFail.flatMap {
              case Left(e) => IO.pure(Left(e))
              case Right(row) =>
                Metrics.count("smohua.responses.submitted", "access" -> f.access, "via" -> s.fold("anonymous")(_.viaString)) *>
                  notifier.responseArrived(f, row, who.map(_.user.name)).start.as(Right(SubmittedDto(row.id, f.confirmation)))
            }
    })

  // ---------------------------------------------------------------- 回答を見る側

  def summary(f: FormRow): IO[FormSummaryStatsDto] =
    Telemetry.span("form.summary", "form.id" -> f.id.toString)((forms.stats(f.id), forms.answers(f.id, SummaryLimit)).mapN((st, as) =>
      FormSummaryStatsDto(f.id, f.title, f.status, st._1, st._2, Forms.summarize(f.questions, as))))

  def deleteResponse(s: AuthSession, id: UUID, responseId: UUID): Res[Unit] =
    access(s, id, write = true).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(_) =>
        forms.deleteResponse(id, responseId).flatMap {
          case false => IO.pure(Left(Fail.NotFound("回答が見つかりません")))
          case true  => activity.audit(Some(s.user.id), s.viaString, "response.delete", Some(responseId.toString), Json.obj("form" -> id.asJson)).as(Right(()))
        }
    }

  /** 連携サービス（skiwi のブロックなど）向け: フォームの題名・状態・回答の数（見られるものだけ）。 */
  def lookup(s: AuthSession, ids: List[UUID]): IO[List[Json]] =
    val wanted = ids.distinct
    for
      found <- forms.findMany(wanted)
      edits <- if s.canAdmin then IO.pure(Set.empty[UUID]) else forms.editableAmong(found.map(_.id), s.user.id)
      seen   = found.filter(f => f.ownerId == s.user.id || s.canAdmin || edits.contains(f.id))
      stats <- forms.statsMany(seen.map(_.id))
      byId   = seen.map(f => f.id -> f).toMap
    yield wanted.flatMap(byId.get).map { f =>
      val (count, last) = stats.getOrElse(f.id, (0, None))
      Json.obj("id" -> f.id.asJson, "title" -> f.title.asJson, "status" -> f.status.asJson, "questions" -> f.questions.size.asJson,
        "responses" -> count.asJson, "lastResponseAt" -> last.asJson, "url" -> s"${cfg.publicUrl}${Paths.responses(f.id)}".asJson)
    }
