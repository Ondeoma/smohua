package smohua.api

import cats.effect.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import smohua.core.*
import sbirds.http.{Errors, RouteHelpers}
import sbirds.shared.AppError

/**
 * *Routes に共通の補助（エラー応答・認証）。本文・クエリ・パスの UUID・302 は sbirds.http.RouteHelpers。
 */
trait RouteSupport extends RouteHelpers {
  protected def sessions: SessionAuth

  protected def bodyInvalid: sbirds.shared.ErrorCode = ErrorCode.RequestBodyInvalid

  // 本文の読み取りの途中のエラー（大きすぎる本文を含む）は、これまでどおり RequestBodyInvalid（400）にする
  override protected def bodyKeepsAppError: Boolean = false

  /** エラーの応答（コード付きでログに出る）. detail を省くとコードの既定の文言. */
  protected def err(code: ErrorCode, detail: String | Null = null): IO[Response[IO]] =
    Errors.respond(AppError(code, Option(detail)))

  protected def fail(e: AppError): IO[Response[IO]] = Errors.respond(e)

  /** 結果が Right なら f、Left なら失敗の応答。 */
  protected def respond[A](r: Res[A])(f: A => IO[Response[IO]]): IO[Response[IO]] = r.flatMap(_.fold(fail, f))

  protected def okJson[A: io.circe.Encoder](r: Res[A]): IO[Response[IO]] = respond(r)(a => Ok(a))

  /** POST だが読み取りだけの API（本文で条件を送るもの）。 */
  private val readOnlyPosts = Set("/api/forms/lookup")
  private def readOnlyPost(req: Request[IO]): Boolean = req.method == Method.POST &&
    readOnlyPosts.contains(req.pathInfo.renderString)

  /** ログインしていなければ 401。更新系の要求を read スコープのキーで送ったら 403。 */
  protected def authed(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(ErrorCode.NotLoggedIn)
      case Some(s) =>
        // 操作したユーザーを、トレース（スパンの属性）とこの中のログに載せる
        sbirds.observe.Telemetry.withUser(s.user.id.toString, s.user.name) {
          if !s.canWrite && !Guards.SafeMethods.contains(req.method) && !readOnlyPost(req) then
            err(ErrorCode.ApiKeyNeedsWrite)
          else f(s)
        }
    }

  /** システム管理者のみ（APIキーは admin スコープが必要）。 */
  protected def admin(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.canAdmin then f(s) else err(ErrorCode.AdminOnly))

  /** Cookie のセッションのみ（APIキーの発行など）。 */
  protected def sessionOnly(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.isSession then f(s) else err(ErrorCode.SessionOnly))

  protected def urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

  /** 同じサイトの中のパスか（ログイン・連携の後に戻る先）。 */
  protected def localPath(p: String): Boolean = sbirds.shared.SafePath.isLocal(p)

}
