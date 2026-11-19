package smohua.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import smohua.core.*
import sbirds.shared.AppError
import smohua.db.*
import sbirds.observe.{Metrics, Telemetry}
import smohua.shared.*

import java.util.UUID

/** フォームの操作（REST・MCP・連携サービスで共通）。権限の確認・保存・監査ログ・通知まで行う。 */
final class FormService(
  forms: FormStore,
  notifier: NotifyService,
  activity: ActivityStore,
  cfg: ApiConfig) {

  private val NotFound = AppError(ErrorCode.FormNotFound)
  private val SummaryLimit = 5000

  /** 持ち主・編集できる人・管理者だけが見られる（それ以外には存在を見せない）。write は変更（参照専用のキーは不可）。 */
  def access(
    s: AuthSession,
    id: UUID,
    write: Boolean = false
  ): Res[FormRow] =
    forms.find(id).flatMap {
      case None => IO.pure(Left(NotFound))
      case Some(f) =>
        visible(s, f).map {
          case false => Left(NotFound)
          case true if write && !s.canWrite => Left(AppError(ErrorCode.ApiKeyReadOnly))
          case true => Right(f)
        }
    }

  /** 持ち主・管理者・編集できる人か。 */
  private def visible(
    s: AuthSession,
    f: FormRow
  ): IO[Boolean] =
    if f.ownerId == s.user.id || s.canAdmin then IO.pure(true) else forms.isEditor(f.id, s.user.id)

  def dto(
    s: AuthSession,
    f: FormRow
  ): IO[FormDto] =
    (forms.editors(f.id), forms.stats(f.id), forms.briefs(List(f.ownerId))).mapN {
      (
        es,
        st,
        owner
      ) =>
        FormDto(
          f.id,
          f.title,
          f.description,
          f.status,
          f.access,
          f.oneResponse,
          f.confirmation,
          f.questions,
          f.notice,
          f.schedule,
          f.appearance,
          owner.headOption.getOrElse(UserBrief(f.ownerId, "", None)),
          es,
          st._1,
          st._2,
          f.createdAt,
          f.updatedAt,
          canEdit = s.canWrite
        )
    }

  def create(
    s: AuthSession,
    r: CreateFormReq
  ): Res[FormDto] =
    (Forms.title(r.title), Forms.normalize(r.questions.getOrElse(Nil))).tupled match {
      case Left(m) => IO.pure(Left(AppError(ErrorCode.FormInvalid, m)))
      case Right((title, qs)) =>
        Telemetry.span("form.create")(forms.create(
          s.user.id,
          title,
          r.description.getOrElse("").trim.take(5000),
          qs
        )).flatMap { f =>
          Metrics.count("smohua.forms.created", "via" -> s.viaString) *>
            activity.audit(
              Some(s.user.id),
              s.viaString,
              "form.create",
              Some(f.id.toString),
              Json.obj("title" -> title.asJson)
            ) *>
            dto(s, f).map(Right(_))
        }
    }

  def update(
    s: AuthSession,
    id: UUID,
    r: UpdateFormReq
  ): Res[FormDto] =
    access(s, id, write = true).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(f) =>
        val status = r.status.getOrElse(f.status)
        val acc = r.access.getOrElse(f.access)
        def invalid(
          m: String
        ) = AppError(ErrorCode.FormInvalid, m)
        val checked: Either[
          AppError,
          (Option[String], Option[List[QuestionDto]], Option[FormScheduleDto], Option[FormAppearanceDto])
        ] =
          for {
            title <- r.title.traverse(Forms.title).left.map(invalid)
            qs <- r.questions.traverse(Forms.normalize).left.map(invalid)
            sch <- r.schedule.traverse(Forms.schedule).left.map(invalid)
            look <- r.appearance.traverse(Forms.appearance).left.map(invalid)
            _ <- Either.cond(
              FormStatus.all.contains(status),
              (),
              AppError(ErrorCode.FormStatusInvalid, s"status は ${FormStatus.all.mkString("・")} のどれかです")
            )
            _ <- Either.cond(
              FormAccess.all.contains(acc),
              (),
              AppError(ErrorCode.FormAccessInvalid, s"access は ${FormAccess.all.mkString("・")} のどれかです")
            )
            _ <-
              Either.cond(
                !(status == FormStatus.Open && qs.getOrElse(f.questions).isEmpty),
                (),
                AppError(ErrorCode.FormNoQuestions)
              )
            _ <- Either.cond(
              !(r.oneResponse.getOrElse(f.oneResponse) && acc == FormAccess.Anyone),
              (),
              AppError(ErrorCode.FormOneResponseNeedsLogin)
            )
            _ <- Either.cond(r.editorIds.forall(_.size <= 50), (), AppError(ErrorCode.FormTooManyEditors))
            _ <-
              Either.cond(
                r.editorIds.isEmpty || f.ownerId == s.user.id || s.canAdmin,
                (),
                AppError(ErrorCode.FormEditorsOwnerOnly)
              )
            // チャンネルへの知らせは持ち主の stui の連携で送るので、送り先は持ち主（と管理者）だけが決める
            _ <- Either.cond(
              r.notice.forall(n => channel(n) == channel(f.notice)) || f.ownerId == s.user.id || s.canAdmin,
              (),
              AppError(ErrorCode.FormChannelOwnerOnly)
            )
          } yield (title, qs, sch, look)
        checked match {
          case Left(e) => IO.pure(Left(e))
          case Right((title, qs, sch, look)) =>
            forms.update(
              id,
              title,
              r.description.map(_.trim.take(5000)),
              r.status,
              r.access,
              r.oneResponse,
              r.confirmation.map(_.trim.take(2000)),
              qs,
              r.notice.map(n => n.copy(stuiChannel = channel(n))),
              r.editorIds,
              sch,
              look
            ).flatMap {
              case None => IO.pure(Left(NotFound))
              case Some(g) =>
                IO.whenA(r.status.exists(_ != f.status))(Metrics.count(
                  "smohua.forms.status_changed",
                  "status" -> status
                ) *>
                  activity.audit(
                    Some(s.user.id),
                    s.viaString,
                    "form.status",
                    Some(id.toString),
                    Json.obj("status" -> status.asJson)
                  )) *>
                  dto(s, g).map(Right(_))
            }
        }
    }

  private def channel(
    n: FormNotifyDto
  ): String = n.stuiChannel.trim.take(100)

  def delete(
    s: AuthSession,
    id: UUID
  ): Res[Unit] =
    access(s, id, write = true).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(f) if f.ownerId != s.user.id && !s.canAdmin => IO.pure(Left(AppError(ErrorCode.FormDeleteNotOwner)))
      case Right(f) =>
        forms.delete(id) *> activity.audit(
          Some(s.user.id),
          s.viaString,
          "form.delete",
          Some(id.toString),
          Json.obj("title" -> f.title.asJson)
        ).as(Right(()))
    }

  /** 質問と設定を写した新しいフォーム（下書き。回答は写さない）。 */
  def duplicate(
    s: AuthSession,
    id: UUID
  ): Res[FormDto] =
    access(s, id, write = true).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(f) =>
        forms.create(s.user.id, (f.title + " のコピー").take(200), f.description, f.questions).flatMap(n =>
          forms.update(
            n.id,
            None,
            None,
            None,
            Some(f.access),
            Some(f.oneResponse),
            Some(f.confirmation),
            None,
            Some(f.notice),
            None,
            appearance = Some(f.appearance)
          )
            .flatMap(g => dto(s, g.getOrElse(n)).map(Right(_)))
        )
    }

  // ---------------------------------------------------------------- 回答する側

  /** 回答する人に見せるフォーム。下書きは、編集できる人だけが見られる（プレビュー）。 */
  def public(
    s: Option[AuthSession],
    id: UUID
  ): Res[PublicFormDto] =
    forms.find(id).flatMap {
      case None => IO.pure(Left(NotFound))
      case Some(f) =>
        val editor = s.fold(IO.pure(false))(visible(_, f))
        val done = s.filter(_ => f.oneResponse).fold(IO.pure(false))(x => forms.answered(id, x.user.id))
        (editor, done).mapN {
          (
            ed,
            answered
          ) =>
            if f.status == FormStatus.Draft && !ed then Left(NotFound)
            else {
              val needLogin = f.access == FormAccess.Login && s.isEmpty
              // ログインが要るフォームは、ログインするまで質問を見せない
              Right(PublicFormDto(
                f.id,
                f.title,
                if needLogin then "" else f.description,
                f.status,
                f.access,
                f.oneResponse,
                if needLogin then Nil else f.questions,
                answered,
                needLogin,
                f.phase(java.time.Instant.now()),
                f.schedule,
                f.appearance
              ))
            }
        }
    }

  def submit(
    s: Option[AuthSession],
    id: UUID,
    r: SubmitReq
  ): Res[SubmittedDto] =
    Telemetry.span("form.submit", "form.id" -> id.toString)(forms.find(id).flatMap {
      case None => IO.pure(Left(NotFound))
      case Some(f) if !f.isOpen =>
        IO.pure(Left(f.phase(java.time.Instant.now()) match {
          case FormPhase.Draft => NotFound
          case FormPhase.Before => AppError(ErrorCode.FormNotYetOpen)
          case _ => AppError(ErrorCode.FormClosed)
        }))
      case Some(f) if f.access == FormAccess.Login && s.isEmpty =>
        IO.pure(Left(AppError(ErrorCode.ResponseLoginRequired)))
      case Some(_) if s.exists(!_.canWrite) => IO.pure(Left(AppError(ErrorCode.ApiKeyReadOnly)))
      case Some(f) =>
        Forms.validate(f.questions, r.answers) match {
          case Left(errs) => IO.pure(Left(AppError(ErrorCode.AnswerInvalid, errs.mkString("\n"))))
          case Right(answers) =>
            // URL を知っている人が答えるフォームは匿名（ログインしていても、だれが答えたかは記録しない）
            val who = s.filter(_ => f.access == FormAccess.Login)
            forms.submit(id, who.map(_.user.id), answers).flatMap {
              case Left(e) => IO.pure(Left(e))
              case Right(row) =>
                Metrics.count(
                  "smohua.responses.submitted",
                  "access" -> f.access,
                  "via" -> s.fold("anonymous")(_.viaString)
                ) *>
                  notifier.responseArrived(
                    f,
                    row,
                    who.map(_.user.name)
                  ).start.as(Right(SubmittedDto(row.id, f.confirmation)))
            }
        }
    })

  // ---------------------------------------------------------------- 回答を見る側

  def summary(
    f: FormRow
  ): IO[FormSummaryStatsDto] =
    Telemetry.span(
      "form.summary",
      "form.id" -> f.id.toString
    )((forms.stats(f.id), forms.answers(f.id, SummaryLimit)).mapN(
      (
        st,
        as
      ) =>
        FormSummaryStatsDto(f.id, f.title, f.status, st._1, st._2, Forms.summarize(f.questions, as))
    ))

  def deleteResponse(
    s: AuthSession,
    id: UUID,
    responseId: UUID
  ): Res[Unit] =
    access(s, id, write = true).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(_) =>
        forms.deleteResponse(id, responseId).flatMap {
          case false => IO.pure(Left(AppError(ErrorCode.ResponseNotFound)))
          case true => activity.audit(
              Some(s.user.id),
              s.viaString,
              "response.delete",
              Some(responseId.toString),
              Json.obj("form" -> id.asJson)
            ).as(Right(()))
        }
    }

  /** 連携サービス（skiwi のブロックなど）向け: フォームの題名・状態・回答の数（見られるものだけ）。 */
  def lookup(
    s: AuthSession,
    ids: List[UUID]
  ): IO[List[Json]] = {
    val wanted = ids.distinct
    for {
      found <- forms.findMany(wanted)
      edits <- if s.canAdmin then IO.pure(Set.empty[UUID]) else forms.editableAmong(found.map(_.id), s.user.id)
      seen = found.filter(f => f.ownerId == s.user.id || s.canAdmin || edits.contains(f.id))
      stats <- forms.statsMany(seen.map(_.id))
      byId = seen.map(f => f.id -> f).toMap
    } yield wanted.flatMap(byId.get).map { f =>
      val (count, last) = stats.getOrElse(f.id, (0, None))
      Json.obj(
        "id" -> f.id.asJson,
        "title" -> f.title.asJson,
        "status" -> f.status.asJson,
        "questions" -> f.questions.size.asJson,
        "responses" -> count.asJson,
        "lastResponseAt" -> last.asJson,
        "url" -> s"${cfg.publicUrl}${Paths.responses(f.id)}".asJson
      )
    }
  }
}
