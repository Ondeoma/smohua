package smohua.web

import io.circe.*
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalajs.dom
import smohua.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** API の呼び出しのエラー. */
final case class ApiError(status: Int, message: String) extends Exception(message)

/** バックエンドの REST API. 401 はログイン画面へ、エラーは {"error": ...} の文言にする. */
object Api {

  /** fetch の設定（同じオリジンの Cookie を付ける. body は JSON の文字列）. */
  def init(method: dom.HttpMethod, body: Option[String] = None): dom.RequestInit = {
    val i = new dom.RequestInit {}
    i.method = method
    i.credentials = dom.RequestCredentials.`same-origin`
    body.foreach { b =>
      i.headers = js.Dictionary("Content-Type" -> "application/json")
      i.body = b
    }
    i
  }

  private def request(method: dom.HttpMethod, url: String, body: Option[Json] = None): Future[Option[Json]] =
    dom.fetch(url, init(method, body.map(_.noSpaces))).toFuture.flatMap { res =>
      if (res.status == 401) {
        val back = dom.window.location.pathname + dom.window.location.search
        // 回答の画面（/f/…）はログインなしでも開けるので、ログイン画面へ送らない（画面の側で「ログインして回答」を出す）
        if (!dom.window.location.pathname.startsWith("/login") && !dom.window.location.pathname.startsWith("/f/"))
          dom.window.location.href = "/auth/login?returnTo=" + js.URIUtils.encodeURIComponent(back)
        Future.failed(ApiError(401, "ログインしてください"))
      } else if (!res.ok) {
        res.text().toFuture.recover(_ => "").flatMap { t =>
          val msg = parse(t).toOption.flatMap(_.hcursor.get[String]("error").toOption).getOrElse(s"${res.status} ${res.statusText}")
          Future.failed(ApiError(res.status, msg))
        }
      } else if (res.status == 204) Future.successful(None)
      else res.text().toFuture.map(t => if (t.isEmpty) None else Some(parse(t).fold(throw _, identity)))
    }

  private def as[A: Decoder](j: Option[Json]): A = j.getOrElse(Json.Null).as[A].fold(e => throw ApiError(0, e.getMessage), identity)

  def get[A: Decoder](url: String): Future[A] = request(dom.HttpMethod.GET, url).map(as[A])
  def post[A: Decoder](url: String, body: Json = Json.obj()): Future[A] = request(dom.HttpMethod.POST, url, Some(body)).map(as[A])
  def patch[A: Decoder](url: String, body: Json): Future[A] = request(dom.HttpMethod.PATCH, url, Some(body)).map(as[A])
  def send(method: dom.HttpMethod, url: String, body: Option[Json] = None): Future[Unit] = request(method, url, body).map(_ => ())
  def delete(url: String): Future[Unit] = send(dom.HttpMethod.DELETE, url)
  def postUnit(url: String, body: Json = Json.obj()): Future[Unit] = send(dom.HttpMethod.POST, url, Some(body))
  def patchUnit(url: String, body: Json): Future[Unit] = send(dom.HttpMethod.PATCH, url, Some(body))

  def enc(s: String): String = js.URIUtils.encodeURIComponent(s)

  def query(params: (String, Option[String])*): String = {
    val q = params.collect { case (k, Some(v)) if v.nonEmpty => s"$k=${enc(v)}" }
    if (q.isEmpty) "" else q.mkString("?", "&", "")
  }

  // ---------------------------------------------------------------- 認証・自分

  def clientConfig(): Future[ClientConfigDto] = get[ClientConfigDto]("/api/client-config")
  def me(): Future[MeDto] = get[MeDto]("/api/me")
  def devLogin(name: String, email: String, admin: Boolean, returnTo: String): Future[Json] =
    post[Json]("/auth/dev-login", Json.obj("name" -> name.asJson, "email" -> email.asJson, "admin" -> admin.asJson, "returnTo" -> returnTo.asJson))
  /** ログアウトして、次に開く URL（IdP のログアウト画面か「/」）を返す. */
  def logout(): Future[String] = post[Json]("/auth/logout").map(_.hcursor.get[String]("redirect").getOrElse("/"))
  def users(q: String, limit: Int = 20): Future[List[UserBrief]] = get[List[UserBrief]]("/api/users" + query("q" -> Some(q), "limit" -> Some(limit.toString)))
  def links(): Future[List[LinkServiceDto]] = get[List[LinkServiceDto]]("/api/integrations/links")

  // ---------------------------------------------------------------- フォーム（作る側）

  def forms(q: String = "", all: Boolean = false): Future[List[FormSummaryDto]] =
    get[List[FormSummaryDto]]("/api/forms" + query("q" -> Some(q), "all" -> Option.when(all)("true")))
  def form(id: UUID): Future[FormDto] = get[FormDto](s"/api/forms/$id")
  def createForm(title: String): Future[FormDto] = post[FormDto]("/api/forms", CreateFormReq(title).asJson.dropNullValues)
  def updateForm(id: UUID, r: UpdateFormReq): Future[FormDto] = patch[FormDto](s"/api/forms/$id", r.asJson.dropNullValues)
  def deleteForm(id: UUID): Future[Unit] = delete(s"/api/forms/$id")
  def duplicateForm(id: UUID): Future[FormDto] = post[FormDto](s"/api/forms/$id/duplicate")
  def responses(id: UUID, before: Option[Long] = None, limit: Int = 100): Future[ResponsePageDto] =
    get[ResponsePageDto](s"/api/forms/$id/responses" + query("before" -> before.map(_.toString), "limit" -> Some(limit.toString)))
  def deleteResponse(id: UUID, responseId: UUID): Future[Unit] = delete(s"/api/forms/$id/responses/$responseId")
  def summary(id: UUID): Future[FormSummaryStatsDto] = get[FormSummaryStatsDto](s"/api/forms/$id/summary")

  // ---------------------------------------------------------------- 回答する側

  def publicForm(id: UUID): Future[PublicFormDto] = get[PublicFormDto](s"/api/public/forms/$id")
  def submit(id: UUID, answers: Map[String, Json]): Future[SubmittedDto] = post[SubmittedDto](s"/api/public/forms/$id/responses", SubmitReq(answers).asJson)

  // ---------------------------------------------------------------- 通知

  def notifications(unread: Boolean = false): Future[NotificationListDto] = get[NotificationListDto]("/api/notifications" + query("unread" -> Option.when(unread)("true")))
  def unread(): Future[Int] = get[Json]("/api/notifications/unread").map(_.hcursor.get[Int]("unread").getOrElse(0))
  def markRead(ids: List[UUID]): Future[Unit] = postUnit("/api/notifications/read", Json.obj("ids" -> ids.asJson))

}
