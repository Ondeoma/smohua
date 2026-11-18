package smohua.core

import java.util.UUID

/** UUID として読めれば Some（読めなければ None）。 */
def uuidOpt(s: String): Option[UUID] = scala.util.Try(UUID.fromString(s.trim)).toOption

/** APIキーのスコープ。 */
enum Scope:
  case Read, Write, Admin

object Scope:
  def parse(s: String): Option[Scope] = s.trim.toLowerCase match
    case "read"  => Some(Read)
    case "write" => Some(Write)
    case "admin" => Some(Admin)
    case _       => None
  extension (s: Scope)
    def asString: String = s match
      case Read  => "read"
      case Write => "write"
      case Admin => "admin"

final case class User(id: UUID, sub: String, name: String, email: Option[String], avatarUrl: Option[String], isAdmin: Boolean,
    disabled: Boolean, timezone: String)
final case class Group(id: UUID, name: String)

/** 認証手段。 */
enum AuthVia:
  case Session
  /** APIキー。scopes はキーのスコープ、client は OAuth で発行したキーのクライアント（skea など）、mcp は MCP 経由の呼び出し。 */
  case ApiKey(id: UUID, scopes: Set[Scope], client: Option[String] = None, mcp: Boolean = false)
  /** サーバーの中の処理。 */
  case System(kind: String)

/** 認証済みの利用者。isAdmin はシステム管理者（ADMIN_GROUPS の所属、または画面で管理者にしたユーザー）。 */
final case class AuthSession(user: User, groups: List[Group], isAdmin: Boolean, via: AuthVia = AuthVia.Session):

  /** 更新系の操作ができるか（read スコープだけのキーは不可）。 */
  def canWrite: Boolean = via match
    case AuthVia.ApiKey(_, scopes, _, _) => scopes.contains(Scope.Write) || scopes.contains(Scope.Admin)
    case _                                => true

  /** システム管理の操作ができるか（APIキーは admin スコープが必要）。 */
  def canAdmin: Boolean = isAdmin && (via match
    case AuthVia.ApiKey(_, scopes, _, _) => scopes.contains(Scope.Admin)
    case _                                => true)

  def viaString: String = via match
    case AuthVia.Session               => "web"
    case AuthVia.ApiKey(_, _, _, true) => "mcp"
    case AuthVia.ApiKey(_, _, Some(_), _) => "app"
    case _: AuthVia.ApiKey             => "api"
    case AuthVia.System(k)             => k

  def isSession: Boolean = via == AuthVia.Session

  def scopeStrings: List[String] = via match
    case AuthVia.ApiKey(_, s, _, _) => s.toList.map(_.asString).sorted
    case _                          => List("read", "write") ++ Option.when(isAdmin)("admin")
