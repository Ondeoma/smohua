package smohua.api

import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import sbirds.auth.Crypto
import smohua.auth.ApiKeys
import smohua.core.*
import smohua.db.*

import java.time.Instant

/** 認証: Cookie のセッション、または `Authorization: Bearer <APIキー>`。 */
final class SessionAuth(
  users: UserStore,
  cfg: ApiConfig) {

  private def isAdmin(
    u: User,
    gs: List[Group]
  ) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  def fromRequest(
    req: Request[IO]
  ): IO[Option[AuthSession]] =
    bearer(req) match {
      case Some(token) =>
        users.findKey(Crypto.sha256(token), Instant.now()).map(_.map {
          (
            u,
            gs,
            kid,
            scopes,
            client
          ) =>
            val mcp = req.pathInfo.renderString.startsWith("/mcp") ||
              req.headers.get(org.typelevel.ci.CIString("X-Smohua-Mcp")).isDefined
            AuthSession(u, gs, isAdmin(u, gs), AuthVia.ApiKey(kid, scopes.flatMap(Scope.parse).toSet, client, mcp))
        })
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content) match {
          case None => IO.pure(None)
          case Some(token) => users.findSession(Crypto.sha256(token), Instant.now()).map(_.map(
              (
                u,
                gs
              ) => AuthSession(u, gs, isAdmin(u, gs))
            ))
        }
    }

  /** 認証がまだ有効か（ログアウト・ユーザーの無効化・キーの失効の後は false）。開いたままの SSE の確認用で、ユーザー・グループは読まない。 */
  def alive(
    req: Request[IO]
  ): IO[Boolean] =
    bearer(req) match {
      case Some(token) => users.keyAlive(Crypto.sha256(token), Instant.now())
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content)
          .fold(IO.pure(false))(token => users.sessionAlive(Crypto.sha256(token), Instant.now()))
    }

  private def bearer(
    req: Request[IO]
  ): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t }.filter(
      ApiKeys.looksLike
    )

  def issue(
    user: User,
    idToken: Option[String] = None
  ): IO[ResponseCookie] = {
    val token = Crypto.randomToken()
    users.createSession(
      Crypto.sha256(token),
      user.id,
      Instant.now().plusSeconds(cfg.sessionTtl.toSeconds),
      idToken
    ).as(cookie(token, cfg.sessionTtl.toSeconds))
  }

  /** sbirds のアプリの表示名（ログインしたときの ID トークンにある. API キーや開発用のログインでは空）. */
  def appNames(
    req: Request[IO]
  ): IO[Map[String, String]] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) =>
        users.sessionIdToken(Crypto.sha256(c.content)).map(_.fold(Map.empty)(sbirds.auth.AppNames.fromIdToken))
      case None => IO.pure(Map.empty)
    }

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(
    req: Request[IO]
  ): IO[(Option[String], ResponseCookie)] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) => users.deleteSession(Crypto.sha256(c.content)).map(_ -> cookie("", 0))
      case None => IO.pure(None -> cookie("", 0))
    }

  private def cookie(
    value: String,
    maxAge: Long
  ): ResponseCookie =
    ResponseCookie(
      cfg.cookieName,
      value,
      maxAge = Some(maxAge),
      path = Some("/"),
      httpOnly = true,
      secure = cfg.cookieSecure,
      sameSite = Some(SameSite.Lax)
    )
}
