package smohua.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import smohua.auth.ApiKeys
import smohua.core.*
import smohua.db.*
import smohua.shared.*

import java.time.Instant
import java.util.UUID

/** 自分の情報・APIキー、ユーザーの一覧、管理画面（ユーザー・監査ログ）。 */
final class UserRoutes(users: UserStore, activity: ActivityStore, val sessions: SessionAuth) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "me" => getMe(req)
    case req @ GET -> Root / "api" / "me" / "api-keys" => getMeApiKeys(req)
    case req @ POST -> Root / "api" / "me" / "api-keys" => postMeApiKeys(req)
    case req @ DELETE -> Root / "api" / "me" / "api-keys" / UuidVar(id) => deleteMeApiKey(req, id)
    case req @ GET -> Root / "api" / "users" => getUsers(req)
    case req @ GET -> Root / "api" / "admin" / "users" => getAdminUsers(req)
    case req @ PATCH -> Root / "api" / "admin" / "users" / UuidVar(id) => patchAdminUser(req, id)
    case req @ GET -> Root / "api" / "admin" / "audit" => getAdminAudit(req)
  }

  private def getMe(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => sessions.appNames(req).flatMap(n => Ok(me(s).copy(appNames = n))))

  private def getMeApiKeys(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => users.keys(s.user.id).flatMap(Ok(_)))

  private def postMeApiKeys(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      body[CreateApiKeyReq](req) { r =>
        val scopes = r.scopes.map(_.trim.toLowerCase).distinct
        if r.name.trim.isEmpty then err(ErrorCode.NameRequired)
        else if r.name.trim.length > 100 then err(ErrorCode.NameTooLong)
        else if r.expiresInDays.exists(d => d < 0 || d > 3650) then err(ErrorCode.ApiKeyExpiryTooLong)
        else if scopes.isEmpty || !scopes.forall(ApiScope.all.contains) then
          err(ErrorCode.ApiKeyScopeInvalid)
        else if scopes.contains(ApiScope.Admin) && !s.isAdmin then err(ErrorCode.ApiKeyAdminScopeAdminOnly)
        else {
          val (key, prefix) = ApiKeys.generate()
          val exp = r.expiresInDays.filter(_ > 0).map(d => Instant.now().plusSeconds(d.toLong * 86400))
          users.createKey(s.user.id, r.name.trim, prefix, Crypto.sha256(key), scopes, exp).flatMap { info =>
            activity.audit(
              Some(s.user.id),
              "web",
              "api_key.create",
              Some(info.id.toString),
              Json.obj("name" -> info.name.asJson, "scopes" -> scopes.asJson)
            ) *>
              Created(CreatedApiKeyDto(key, info))
          }
        }
      }
    )

  private def deleteMeApiKey(req: Request[IO], id: UUID): IO[Response[IO]] =
    sessionOnly(req)(s =>
      users.revokeKey(s.user.id, id).flatMap {
        case true => activity.audit(Some(s.user.id), "web", "api_key.revoke", Some(id.toString), Json.obj()) *>
            NoContent()
        case false => err(ErrorCode.ApiKeyNotFound)
      }
    )

  private def getUsers(req: Request[IO]): IO[Response[IO]] =
    authed(req)(_ =>
      users.list(param(req, "q"), includeDisabled = false, intParam(req, "limit", 50, 500)).flatMap(us =>
        Ok(us.map(u => UserBrief(u.id, u.name, u.avatarUrl)))
      )
    )

  private def getAdminUsers(req: Request[IO]): IO[Response[IO]] =
    admin(req)(_ => users.list(param(req, "q"), includeDisabled = true, 2000).flatMap(Ok(_)))

  private def patchAdminUser(req: Request[IO], id: UUID): IO[Response[IO]] =
    admin(req)(s =>
      body[UpdateUserReq](req) { r =>
        if id == s.user.id && (r.isAdmin.contains(false) || r.disabled.contains(true)) then
          err(ErrorCode.UserSelfDemotion)
        else
          users.update(id, r.isAdmin, r.disabled).flatMap {
            case true => activity.audit(Some(s.user.id), s.viaString, "user.update", Some(id.toString), r.asJson) *>
                NoContent()
            case false => err(ErrorCode.UserNotFound)
          }
      }
    )

  private def getAdminAudit(req: Request[IO]): IO[Response[IO]] =
    admin(req)(_ =>
      activity.auditLog(
        param(req, "before").flatMap(_.toLongOption),
        param(req, "action"),
        intParam(req, "limit", 100, 500)
      ).flatMap(Ok(_))
    )

  private def me(s: AuthSession): MeDto =
    MeDto(
      s.user.id,
      s.user.name,
      s.user.email,
      s.user.avatarUrl,
      s.isAdmin,
      s.user.timezone,
      s.viaString,
      s.scopeStrings
    )

}
