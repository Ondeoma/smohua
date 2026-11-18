package smohua.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Authorization
import smohua.auth.{ApiKeys, Crypto}
import smohua.core.*
import smohua.db.*
import smohua.shared.ErrorDto

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

final case class ApiConfig(
    adminGroups: Set[String],
    cookieSecure: Boolean,
    publicUrl: String,
    sessionTtl: FiniteDuration = 14.days,
    cookieName: String = "smohua_session",
    devLogin: Boolean = false,
    timezone: String = "Asia/Tokyo",
    version: String = "0.1.0",
    oauthClients: List[OAuthClient] = Nil,
    stui: Option[LinkService] = None,
    mail: Boolean = false,
    rateLimit: RateLimitConfig = RateLimitConfig()
):
  /** 開発中（DEV_LOGIN）だけ、画面の開発サーバー（http://localhost:…・http://127.0.0.1:…）も自分のサイトとして扱う。 */
  def devOrigin(url: String): Boolean = devLogin && (url.startsWith("http://localhost:") || url.startsWith("http://127.0.0.1:"))

/**
 * 連携する兄弟のサービス（stui: 回答が届いたときの通知）。相手に smohua をクライアントとして登録しておく。
 * url は画面から開く URL、internalUrl はサーバーから呼ぶ URL（省略時は url）。
 */
final case class LinkService(provider: String, url: String, clientId: String, clientSecret: String, internalUrl: Option[String] = None):
  def apiUrl: String = internalUrl.getOrElse(url)

/** 認可を求める外部のサービス（OAUTH_CLIENTS で登録する。skiwi など）。redirectUri は完全一致で照合する。scopes は許すスコープ（read・write）。 */
final case class OAuthClient(id: String, secret: String, redirectUri: String, name: String, scopes: List[String] = List("read"))

/** 処理の失敗（HTTP の応答に変換する）。 */
enum Fail:
  case NotFound(msg: String)
  case Forbidden(msg: String)
  case Invalid(msg: String)
  case Conflict(msg: String)
  case Unauthorized(msg: String)

object Fail:
  def from(e: StoreError): Fail = e match
    case StoreError.NotFound(w) => Fail.NotFound(s"$w が見つかりません")
    case StoreError.Invalid(m)  => Fail.Invalid(m)
    case StoreError.Conflict(m) => Fail.Conflict(m)

type Res[A] = IO[Either[Fail, A]]

extension [A](io: IO[Either[StoreError, A]]) def orFail: Res[A] = io.map(_.left.map(Fail.from))

/** 認証: Cookie のセッション、または `Authorization: Bearer <APIキー>`。 */
final class SessionAuth(users: UserStore, cfg: ApiConfig):

  private def isAdmin(u: User, gs: List[Group]) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  def fromRequest(req: Request[IO]): IO[Option[AuthSession]] =
    bearer(req) match
      case Some(token) =>
        users.findKey(Crypto.sha256(token), Instant.now()).map(_.map { (u, gs, kid, scopes, client) =>
          val mcp = req.pathInfo.renderString.startsWith("/mcp") || req.headers.get(org.typelevel.ci.CIString("X-Smohua-Mcp")).isDefined
          AuthSession(u, gs, isAdmin(u, gs), AuthVia.ApiKey(kid, scopes.flatMap(Scope.parse).toSet, client, mcp))
        })
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content) match
          case None        => IO.pure(None)
          case Some(token) => users.findSession(Crypto.sha256(token), Instant.now()).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))

  /** 認証がまだ有効か（ログアウト・ユーザーの無効化・キーの失効の後は false）。開いたままの SSE の確認用で、ユーザー・グループは読まない。 */
  def alive(req: Request[IO]): IO[Boolean] =
    bearer(req) match
      case Some(token) => users.keyAlive(Crypto.sha256(token), Instant.now())
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content)
          .fold(IO.pure(false))(token => users.sessionAlive(Crypto.sha256(token), Instant.now()))

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t }.filter(ApiKeys.looksLike)

  def issue(user: User, idToken: Option[String] = None): IO[ResponseCookie] =
    val token = Crypto.randomToken()
    users.createSession(Crypto.sha256(token), user.id, Instant.now().plusSeconds(cfg.sessionTtl.toSeconds), idToken).as(cookie(token, cfg.sessionTtl.toSeconds))

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(req: Request[IO]): IO[(Option[String], ResponseCookie)] =
    req.cookies.find(_.name == cfg.cookieName) match
      case Some(c) => users.deleteSession(Crypto.sha256(c.content)).map(_ -> cookie("", 0))
      case None    => IO.pure(None -> cookie("", 0))

  private def cookie(value: String, maxAge: Long): ResponseCookie =
    ResponseCookie(cfg.cookieName, value, maxAge = Some(maxAge), path = Some("/"), httpOnly = true, secure = cfg.cookieSecure,
      sameSite = Some(SameSite.Lax))

/** *Routes に共通の補助（エラー応答・認証・リクエスト本文）。 */
trait RouteSupport extends Http4sDsl[IO]:
  protected def sessions: SessionAuth

  protected def err(status: Status, msg: String): IO[Response[IO]] =
    Response[IO](status).withEntity(ErrorDto(msg)).pure[IO]

  protected def fail(f: Fail): IO[Response[IO]] = f match
    case Fail.NotFound(m)     => err(Status.NotFound, m)
    case Fail.Forbidden(m)    => err(Status.Forbidden, m)
    case Fail.Invalid(m)      => err(Status.BadRequest, m)
    case Fail.Conflict(m)     => err(Status.Conflict, m)
    case Fail.Unauthorized(m) => err(Status.Unauthorized, m)

  /** 結果が Right なら f、Left なら失敗の応答。 */
  protected def respond[A](r: Res[A])(f: A => IO[Response[IO]]): IO[Response[IO]] = r.flatMap(_.fold(fail, f))

  protected def okJson[A: io.circe.Encoder](r: Res[A]): IO[Response[IO]] = respond(r)(a => Ok(a))

  /** POST だが読み取りだけの API（本文で条件を送るもの）。 */
  private val readOnlyPosts = Set("/api/forms/lookup")
  private def readOnlyPost(req: Request[IO]): Boolean = req.method == Method.POST && readOnlyPosts.contains(req.pathInfo.renderString)

  /** ログインしていなければ 401。更新系の要求を read スコープのキーで送ったら 403。 */
  protected def authed(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(Status.Unauthorized, "ログインしてください")
      case Some(s) =>
        // 操作したユーザーを、トレース（スパンの属性）とこの中のログに載せる
        smohua.observe.Telemetry.withUser(s.user.id.toString, s.user.name) {
          if !s.canWrite && !Guards.SafeMethods.contains(req.method) && !readOnlyPost(req) then
            err(Status.Forbidden, "このAPIキーは参照専用です（write スコープが必要です）")
          else f(s)
        }
    }

  /** システム管理者のみ（APIキーは admin スコープが必要）。 */
  protected def admin(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.canAdmin then f(s) else err(Status.Forbidden, "管理者だけが使えます"))

  /** Cookie のセッションのみ（APIキーの発行など）。 */
  protected def sessionOnly(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.isSession then f(s) else err(Status.Forbidden, "この操作は画面からのみ行えます"))

  protected def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] =
    req.as[A].attempt.flatMap {
      case Left(e)  => err(Status.BadRequest, s"リクエストの内容が正しくありません: ${e.getMessage.take(200)}")
      case Right(b) => f(b)
    }

  protected object UuidVar:
    def unapply(s: String): Option[UUID] = uuidOpt(s)

  /** 302 で移す（Cookie を付けるときは cookie）。 */
  protected def redirect(to: String, cookie: Option[ResponseCookie] = None): IO[Response[IO]] =
    val r = Response[IO](Status.Found).putHeaders(org.http4s.headers.Location(Uri.unsafeFromString(to)))
    IO.pure(cookie.fold(r)(r.addCookie))

  protected def urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

  /** 同じサイトの中のパスか（ログイン・連携の後に戻る先）。 */
  protected def localPath(p: String): Boolean = smohua.shared.Paths.isLocal(p)

  protected def param(req: Request[IO], k: String): Option[String] = req.params.get(k).map(_.trim).filter(_.nonEmpty)

  protected def intParam(req: Request[IO], k: String, default: Int, max: Int): Int =
    param(req, k).flatMap(_.toIntOption).map(v => math.max(1, math.min(v, max))).getOrElse(default)
