package smohua.web

import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import smohua.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** バックエンドの REST API（共通部分は sbirds-lib の ApiBase）. */
object Api extends sbirds.web.ApiBase {

  // 回答の画面（/f/…）はログインなしでも開けるので、ログイン画面へ送らない（画面の側で「ログインして回答」を出す）
  override protected def stayOnUnauthorized: Boolean =
    super.stayOnUnauthorized || dom.window.location.pathname.startsWith("/f/")

  // ---------------------------------------------------------------- 認証・自分

  def clientConfig(): Future[ClientConfigDto] = get[ClientConfigDto]("/api/client-config")
  def me(): Future[MeDto] = get[MeDto]("/api/me")
  def devLogin(name: String, email: String, admin: Boolean, returnTo: String): Future[Json] =
    post[Json](
      "/auth/dev-login",
      Json.obj("name" -> name.asJson, "email" -> email.asJson, "admin" -> admin.asJson, "returnTo" -> returnTo.asJson)
    )

  /** ログアウトして、次に開く URL（IdP のログアウト画面か「/」）を返す. */
  def logout(): Future[String] = post[Json]("/auth/logout").map(_.hcursor.get[String]("redirect").getOrElse("/"))
  def users(q: String, limit: Int = 20): Future[List[UserBrief]] =
    get[List[UserBrief]]("/api/users" + query("q" -> Some(q), "limit" -> Some(limit.toString)))
  def links(): Future[List[LinkServiceDto]] = get[List[LinkServiceDto]]("/api/integrations/links")

  // ---------------------------------------------------------------- フォーム（作る側）

  def forms(q: String = "", all: Boolean = false): Future[List[FormSummaryDto]] =
    get[List[FormSummaryDto]]("/api/forms" + query("q" -> Some(q), "all" -> Option.when(all)("true")))
  def form(id: UUID): Future[FormDto] = get[FormDto](s"/api/forms/$id")
  def createForm(title: String): Future[FormDto] =
    post[FormDto]("/api/forms", CreateFormReq(title).asJson.dropNullValues)
  def updateForm(id: UUID, r: UpdateFormReq): Future[FormDto] =
    patch[FormDto](s"/api/forms/$id", r.asJson.dropNullValues)
  def deleteForm(id: UUID): Future[Unit] = delete(s"/api/forms/$id")
  def duplicateForm(id: UUID): Future[FormDto] = post[FormDto](s"/api/forms/$id/duplicate")
  def responses(id: UUID, before: Option[Long] = None, limit: Int = 100): Future[ResponsePageDto] =
    get[ResponsePageDto](s"/api/forms/$id/responses" +
      query("before" -> before.map(_.toString), "limit" -> Some(limit.toString)))
  def deleteResponse(id: UUID, responseId: UUID): Future[Unit] = delete(s"/api/forms/$id/responses/$responseId")
  def summary(id: UUID): Future[FormSummaryStatsDto] = get[FormSummaryStatsDto](s"/api/forms/$id/summary")

  // 通知先（持ち主と管理者だけ）
  def targets(id: UUID): Future[List[FormTargetDto]] = get[List[FormTargetDto]](s"/api/forms/$id/targets")
  def createTarget(id: UUID, r: FormTargetReq): Future[FormTargetDto] =
    post[FormTargetDto](s"/api/forms/$id/targets", r.asJson.dropNullValues)
  def updateTarget(id: UUID, tid: UUID, r: FormTargetReq): Future[FormTargetDto] =
    put[FormTargetDto](s"/api/forms/$id/targets/$tid", r.asJson.dropNullValues)
  def deleteTarget(id: UUID, tid: UUID): Future[Unit] = delete(s"/api/forms/$id/targets/$tid")
  def testTarget(id: UUID, tid: UUID): Future[Unit] = postUnit(s"/api/forms/$id/targets/$tid/test")

  // ---------------------------------------------------------------- 回答する側

  def publicForm(id: UUID): Future[PublicFormDto] = get[PublicFormDto](s"/api/public/forms/$id")
  def submit(id: UUID, answers: Map[String, Json]): Future[SubmittedDto] =
    post[SubmittedDto](s"/api/public/forms/$id/responses", SubmitReq(answers).asJson)

  // ---------------------------------------------------------------- 通知

  def notifications(unread: Boolean = false): Future[NotificationListDto] =
    get[NotificationListDto]("/api/notifications" + query("unread" -> Option.when(unread)("true")))
  def unread(): Future[Int] = get[Json]("/api/notifications/unread").map(_.hcursor.get[Int]("unread").getOrElse(0))
  def markRead(ids: List[UUID]): Future[Unit] = postUnit("/api/notifications/read", Json.obj("ids" -> ids.asJson))

}
