package smohua.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import smohua.core.*
import smohua.shared.*

import java.time.Instant
import java.util.UUID

/** ユーザー・グループ・セッション・ログイン途中の状態・APIキー。 */
final class UserStore(xa: Transactor[IO]) extends TxStore(xa) {

  private val userCols = fr"u.id, u.sub, u.name, u.email, u.avatar_url, u.is_admin, u.disabled, u.timezone"

  // ---------------------------------------------------------------- ユーザー

  /**
   * ログイン時に作成・更新する。アイコン（avatar）は IdP（sruru）で登録したもの（picture）をそのまま写す。IdP で外されていたら（None）こちらも外す。
   * timezone は新しく作るときだけ使う。
   */
  def upsert(sub: String,
             name: String,
             email: Option[String],
             avatar: Option[String],
             timezone: String = "Asia/Tokyo"): IO[User] = tx(
    sql"""insert into app_user (id, sub, name, email, avatar_url, timezone, last_login_at)
          values (${UUID.randomUUID()}, $sub, $name, $email, $avatar, $timezone, now())
          on conflict (sub) do update set name = excluded.name, email = excluded.email, avatar_url = excluded.avatar_url,
            last_login_at = now()
          returning id, sub, name, email, avatar_url, is_admin, disabled, timezone""".query[User].unique
  )

  def find(id: UUID): IO[Option[User]] = tx(findC(id))

  def findC(id: UUID): ConnectionIO[Option[User]] =
    (fr"select" ++ userCols ++ fr"from app_user u where u.id = $id").query[User].option

  /** ログイン時に IdP の groups クレームで所属を置き換える。 */
  def syncGroups(userId: UUID, names: List[String]): IO[Unit] = tx {
    val ns = names.map(_.trim).filter(_.nonEmpty).distinct
    for {
      ids <- ns.traverse(n =>
        sql"""insert into user_group (id, name) values (${UUID.randomUUID()}, $n)
              on conflict (name) do update set name = excluded.name returning id""".query[UUID].unique
      )
      _ <- sql"delete from group_member where user_id = $userId".update.run
      _ <- ids.traverse_(g =>
        sql"insert into group_member (group_id, user_id) values ($g, $userId) on conflict do nothing".update.run
      )
    } yield ()
  }

  /** 一覧（管理画面・編集できる人の候補）。q で名前・メールアドレスを絞る。 */
  def list(q: Option[String], includeDisabled: Boolean, limit: Int = 500): IO[List[UserDto]] = tx {
    val where = List(
      q.map(_.trim).filter(_.nonEmpty).map(s =>
        fr"(u.name ilike ${Sql.containing(s)} or u.email ilike ${Sql.containing(s)})"
      ),
      Option.when(!includeDisabled)(fr"not u.disabled")
    ).flatten
    (fr"""select u.id, u.name, u.email, u.avatar_url, u.is_admin, u.disabled, u.last_login_at,
                 coalesce((select array_agg(g.name order by g.name) from user_group g join group_member m on m.group_id = g.id where m.user_id = u.id), '{}')
          from app_user u""" ++ Sql.whereAnd(where) ++ fr"order by u.name limit $limit")
      .query[(UUID, String, Option[String], Option[String], Boolean, Boolean, Option[Instant], List[String])]
      .map((id, n, e, a, ad, d, l, g) => UserDto(id, n, e, a, ad, d, l, g)).to[List]
  }

  def update(id: UUID, isAdmin: Option[Boolean], disabled: Option[Boolean]): IO[Boolean] = tx {
    val sets = List(isAdmin.map(v => fr"is_admin = $v"), disabled.map(v => fr"disabled = $v")).flatten
    if sets.isEmpty then findC(id).map(_.isDefined)
    else
      for {
        n <- (fr"update app_user" ++ Sql.set(sets) ++ fr"where id = $id").update.run
        _ <- if disabled.contains(true) then sql"delete from session where user_id = $id".update.run
        else 0.pure[ConnectionIO]
      } yield n > 0
  }

  // ---------------------------------------------------------------- セッション

  def createSession(hash: Array[Byte], userId: UUID, expires: Instant, idToken: Option[String] = None): IO[Unit] = tx(
    sql"insert into session (token_hash, user_id, expires_at, id_token) values ($hash, $userId, $expires, $idToken)".update.run.void
  )

  /** ユーザーと所属するグループを1回で読む列（u は app_user。グループは名前の順）。 */
  private val userWithGroups = userCols ++
    fr""",
    coalesce((select array_agg(g.id order by g.name) from user_group g join group_member m on m.group_id = g.id where m.user_id = u.id), '{}'),
    coalesce((select array_agg(g.name order by g.name) from user_group g join group_member m on m.group_id = g.id where m.user_id = u.id), '{}')"""

  private def withGroups(u: User, ids: List[UUID], names: List[String]): (User, List[Group]) =
    (u, ids.zip(names).map(Group(_, _)))

  def findSession(hash: Array[Byte], now: Instant): IO[Option[(User, List[Group])]] = tx(
    (fr"select" ++ userWithGroups ++ fr"""from session s join app_user u on u.id = s.user_id
      where s.token_hash = $hash and s.expires_at > $now and not u.disabled""")
      .query[(User, List[UUID], List[String])].option.map(_.map(withGroups))
  )

  /** セッションがまだ有効か（ユーザーが無効にされていないか）だけを確かめる（開いたままの SSE の確認用。グループは読まない）。 */
  def sessionAlive(hash: Array[Byte], now: Instant): IO[Boolean] = tx(
    sql"""select exists(select 1 from session s join app_user u on u.id = s.user_id
          where s.token_hash = $hash and s.expires_at > $now and not u.disabled)""".query[Boolean].unique
  )

  /** セッションの id_token（OIDC でログインしたとき）. */
  def sessionIdToken(hash: Array[Byte]): IO[Option[String]] =
    tx(sql"select id_token from session where token_hash = $hash".query[Option[String]].option.map(_.flatten))

  /** セッションを消し、IdP からログアウトするための id_token を返す. */
  def deleteSession(hash: Array[Byte]): IO[Option[String]] =
    tx(sql"delete from session where token_hash = $hash returning id_token".query[Option[String]].option.map(_.flatten))

  def purgeExpired(now: Instant): IO[Unit] = tx(sql"delete from session where expires_at < $now".update.run *>
    sql"delete from login_state where created_at < now() - interval '1 hour'".update.run.void)

  // ---------------------------------------------------------------- ログイン途中の状態（OIDC）

  def saveLoginState(state: String, verifier: String, nonce: String, returnTo: String): IO[Unit] = tx(
    sql"insert into login_state (state, verifier, nonce, return_to) values ($state, $verifier, $nonce, $returnTo)".update.run.void
  )

  /** 取り出して消す（1回だけ使える）。 */
  def takeLoginState(state: String): IO[Option[(String, String, String)]] = tx(
    sql"""delete from login_state where state = $state and created_at > now() - interval '15 minutes'
          returning verifier, nonce, return_to""".query[(String, String, String)].option
  )

  // ---------------------------------------------------------------- APIキー

  def createKey(userId: UUID,
                name: String,
                prefix: String,
                hash: Array[Byte],
                scopes: List[String],
                expires: Option[Instant]): IO[ApiKeyDto] = tx(
    sql"""insert into api_key (id, user_id, name, prefix, key_hash, scopes, expires_at)
          values (${UUID.randomUUID()}, $userId, $name, $prefix, $hash, ${scopes.toArray}, $expires)
          returning id, name, prefix, scopes, created_at, expires_at, last_used_at, oauth_client""".query[ApiKeyDto].unique
  )

  def keys(userId: UUID): IO[List[ApiKeyDto]] = tx(
    sql"""select id, name, prefix, scopes, created_at, expires_at, last_used_at, oauth_client from api_key
          where user_id = $userId and revoked_at is null order by created_at desc""".query[ApiKeyDto].to[List]
  )

  def revokeKey(userId: UUID, id: UUID): IO[Boolean] = tx(
    sql"update api_key set revoked_at = now() where id = $id and user_id = $userId and revoked_at is null".update.run.map(
      _ > 0
    )
  )

  /** 有効なキー（ユーザー・グループ・キーの ID・スコープ・クライアント）。最終使用日時は1分単位で記録する。 */
  def findKey(hash: Array[Byte], now: Instant): IO[Option[(User, List[Group], UUID, List[String], Option[String])]] =
    tx(
      (fr"select k.id, k.scopes, k.oauth_client," ++ userWithGroups ++
        fr"""from api_key k join app_user u on u.id = k.user_id
      where k.key_hash = $hash and k.revoked_at is null and (k.expires_at is null or k.expires_at > $now) and not u.disabled""")
        .query[(UUID, List[String], Option[String], User, List[UUID], List[String])].option.flatMap {
          case None => none.pure[ConnectionIO]
          case Some((kid, scopes, client, u, gids, gnames)) =>
            val (user, gs) = withGroups(u, gids, gnames)
            sql"update api_key set last_used_at = $now where id = $kid and (last_used_at is null or last_used_at < $now - interval '1 minute')"
              .update.run.as(Some((user, gs, kid, scopes, client)))
        }
    )

  /** キーがまだ有効か（失効・期限切れ・ユーザーの無効化）だけを確かめる（開いたままの SSE の確認用）。 */
  def keyAlive(hash: Array[Byte], now: Instant): IO[Boolean] = tx(
    sql"""select exists(select 1 from api_key k join app_user u on u.id = k.user_id
          where k.key_hash = $hash and k.revoked_at is null and (k.expires_at is null or k.expires_at > $now) and not u.disabled)"""
      .query[Boolean].unique
  )
}
