package smohua.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import smohua.auth.{ApiKeys, Crypto, OidcClient}
import smohua.core.*
import smohua.db.*
import smohua.shared.*

import java.time.Instant

/** ログイン（OIDC・開発用）・ログアウト。 */
final class AuthRoutes(oidc: Option[OidcClient], users: UserStore, activity: ActivityStore, val sessions: SessionAuth, cfg: ApiConfig)
    extends RouteSupport:

  /** ログイン後に戻る先（同じサイトのパスだけ）。 */
  private def safeReturn(s: Option[String]): String =
    s.filter(localPath).getOrElse("/")

  private val LoginCookie = "smohua_login_state"

  /** ログインした: セッションを発行する。 */
  private def loggedIn(u: User, via: String, idToken: Option[String] = None): IO[ResponseCookie] =
    sessions.issue(u, idToken).flatTap(_ => activity.audit(Some(u.id), via, "auth.login", None, Json.obj()) *> smohua.observe.Metrics.count("smohua.logins", "via" -> via))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "auth" / "login" =>
      val back = safeReturn(param(req, "returnTo"))
      oidc match
        case Some(o) =>
          val state = Crypto.randomToken(); val verifier = Crypto.randomToken(48); val nonce = Crypto.randomToken()
          // state をこのブラウザの Cookie にも入れ、戻ってきたときに照合する（他人のログインを踏ませる攻撃を防ぐ）
          users.saveLoginState(state, verifier, nonce, back) *> o.authorizationUri(state, verifier, nonce).flatMap(u =>
            redirect(u.renderString, Some(ResponseCookie(LoginCookie, state, path = Some("/auth"), httpOnly = true, secure = cfg.cookieSecure,
              sameSite = Some(SameSite.Lax), maxAge = Some(900)))))
        case None if cfg.devLogin => redirect(s"/login?returnTo=${urlEncode(back)}")
        case None                 => err(Status.ServiceUnavailable, "OIDC が設定されていません")

    case req @ GET -> Root / "auth" / "callback" =>
      (oidc, param(req, "code"), param(req, "state")) match
        case (Some(_), _, Some(state)) if !req.cookies.find(_.name == LoginCookie).exists(c => Crypto.constantTimeEquals(c.content, state)) =>
          err(Status.BadRequest, "ログインの有効期限が切れました。もう一度ログインしてください")
        case (Some(o), Some(code), Some(state)) =>
          users.takeLoginState(state).flatMap {
            case None => err(Status.BadRequest, "ログインの有効期限が切れました。もう一度ログインしてください")
            case Some((verifier, nonce, back)) =>
              o.authenticate(code, verifier, nonce, Instant.now()).attempt.flatMap {
                case Left(e) =>
                  // 詳しい理由（IdP の応答など）は監査ログにだけ残し、画面には出さない
                  activity.audit(None, "web", "auth.denied", None, Json.obj("error" -> String.valueOf(e.getMessage).take(500).asJson)) *>
                    err(Status.Unauthorized, "ログインできませんでした。もう一度ログインしてください")
                case Right(id) =>
                  for
                    // アイコンは IdP（sruru）の picture をそのまま写す（外されていれば外す）
                    u <- users.upsert(id.sub, id.name, id.email, id.picture, cfg.timezone)
                    r <- if u.disabled then err(Status.Forbidden, "このユーザーは無効にされています")
                         else users.syncGroups(u.id, id.groups) *> loggedIn(u, "web", id.idToken).flatMap(c => redirect(back, Some(c)))
                  yield r
              }
          }
        case (None, _, _) => err(Status.ServiceUnavailable, "OIDC が設定されていません")
        case _            => err(Status.BadRequest, "code と state が必要です")

    // ログアウト: セッションを消し、IdP にもログアウトを頼む（IdP のログインが残っていると、すぐにログインし直してしまうため）
    case req @ POST -> Root / "auth" / "logout" =>
      sessions.revoke(req).flatMap { (idToken, c) =>
        oidc.flatTraverse(_.logoutUri(idToken, s"${cfg.publicUrl.stripSuffix("/")}/")).flatMap { idp =>
          Ok(Json.obj("redirect" -> idp.fold("/")(_.renderString).asJson)).map(_.addCookie(c))
        }
      }

    // 開発用: 名前だけでログインする（DEV_LOGIN=true のときだけ）
    case req @ POST -> Root / "auth" / "dev-login" if cfg.devLogin =>
      body[Json](req) { j =>
        val c     = j.hcursor
        val name  = c.get[String]("name").getOrElse("").trim
        val admin = c.get[Boolean]("admin").getOrElse(false)
        if name.isEmpty then err(Status.BadRequest, "名前を入力してください")
        else if name.length > 100 then err(Status.BadRequest, "名前は100文字までにしてください")
        else
          for
            u0 <- users.upsert(s"dev:$name", name, c.get[String]("email").toOption.filter(_.contains("@")), None, cfg.timezone)
            _  <- users.update(u0.id, Option.when(admin)(true), None)
            u  <- users.find(u0.id).map(_.getOrElse(u0))
            ck <- loggedIn(u, "web")
            r  <- Ok(Json.obj("redirect" -> safeReturn(c.get[String]("returnTo").toOption).asJson)).map(_.addCookie(ck))
          yield r
      }

    case GET -> Root / "api" / "client-config" =>
      Ok(ClientConfigDto(cfg.devLogin && oidc.isEmpty, cfg.version, cfg.mail, cfg.stui.isDefined))
  }

/** 自分の情報・APIキー、ユーザーの一覧、管理画面（ユーザー・監査ログ）。 */
final class UserRoutes(users: UserStore, activity: ActivityStore, val sessions: SessionAuth) extends RouteSupport:

  private def me(s: AuthSession): MeDto =
    MeDto(s.user.id, s.user.name, s.user.email, s.user.avatarUrl, s.isAdmin, s.user.timezone, s.viaString, s.scopeStrings)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "me" => authed(req)(s => Ok(me(s)))

    // ---------------------------------------------------------------- APIキー（画面からのみ）

    case req @ GET -> Root / "api" / "me" / "api-keys" => sessionOnly(req)(s => users.keys(s.user.id).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "me" / "api-keys" =>
      sessionOnly(req)(s => body[CreateApiKeyReq](req) { r =>
        val scopes = r.scopes.map(_.trim.toLowerCase).distinct
        if r.name.trim.isEmpty then err(Status.BadRequest, "名前を入力してください")
        else if r.name.trim.length > 100 then err(Status.BadRequest, "名前は100文字までにしてください")
        else if r.expiresInDays.exists(d => d < 0 || d > 3650) then err(Status.BadRequest, "有効期限は3650日までにしてください")
        else if scopes.isEmpty || !scopes.forall(ApiScope.all.contains) then err(Status.BadRequest, "スコープは read / write / admin から選んでください")
        else if scopes.contains(ApiScope.Admin) && !s.isAdmin then err(Status.Forbidden, "admin スコープは管理者だけが使えます")
        else
          val (key, prefix) = ApiKeys.generate()
          val exp = r.expiresInDays.filter(_ > 0).map(d => Instant.now().plusSeconds(d.toLong * 86400))
          users.createKey(s.user.id, r.name.trim, prefix, Crypto.sha256(key), scopes, exp).flatMap { info =>
            activity.audit(Some(s.user.id), "web", "api_key.create", Some(info.id.toString), Json.obj("name" -> info.name.asJson, "scopes" -> scopes.asJson)) *>
              Created(CreatedApiKeyDto(key, info))
          }
      })

    case req @ DELETE -> Root / "api" / "me" / "api-keys" / UuidVar(id) =>
      sessionOnly(req)(s => users.revokeKey(s.user.id, id).flatMap {
        case true  => activity.audit(Some(s.user.id), "web", "api_key.revoke", Some(id.toString), Json.obj()) *> NoContent()
        case false => err(Status.NotFound, "APIキーが見つかりません")
      })

    // ---------------------------------------------------------------- ユーザー

    case req @ GET -> Root / "api" / "users" =>
      authed(req)(_ => users.list(param(req, "q"), includeDisabled = false, intParam(req, "limit", 50, 500)).flatMap(us =>
        Ok(us.map(u => UserBrief(u.id, u.name, u.avatarUrl)))))

    case req @ GET -> Root / "api" / "admin" / "users" =>
      admin(req)(_ => users.list(param(req, "q"), includeDisabled = true, 2000).flatMap(Ok(_)))

    case req @ PATCH -> Root / "api" / "admin" / "users" / UuidVar(id) =>
      admin(req)(s => body[UpdateUserReq](req) { r =>
        if id == s.user.id && (r.isAdmin.contains(false) || r.disabled.contains(true)) then err(Status.BadRequest, "自分自身を無効にしたり、管理者権限を外したりはできません")
        else users.update(id, r.isAdmin, r.disabled).flatMap {
          case true  => activity.audit(Some(s.user.id), s.viaString, "user.update", Some(id.toString), r.asJson) *> NoContent()
          case false => err(Status.NotFound, "ユーザーが見つかりません")
        }
      })

    case req @ GET -> Root / "api" / "admin" / "audit" =>
      admin(req)(_ => activity.auditLog(param(req, "before").flatMap(_.toLongOption), param(req, "action"), intParam(req, "limit", 100, 500)).flatMap(Ok(_)))
  }
