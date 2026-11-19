package smohua.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import smohua.shared.*

import java.time.Instant
import java.util.UUID

/** 監査ログ。 */
final class ActivityStore(xa: Transactor[IO]) extends TxStore(xa) {

  // ---------------------------------------------------------------- 監査ログ

  def audit(actor: Option[UUID], via: String, action: String, target: Option[String], detail: Json): IO[Unit] = tx(
    sql"insert into audit_log (actor_id, via, action, target, detail) values ($actor, $via, $action, $target, $detail)".update.run.void
  )

  def auditLog(before: Option[Long], action: Option[String], limit: Int): IO[List[AuditDto]] = tx(
    (fr"""select a.id, a.at, u.id, u.name, u.avatar_url, a.via, a.action, a.target, a.detail
          from audit_log a left join app_user u on u.id = a.actor_id where true""" ++
      before.fold(Fragment.empty)(b => fr"and a.id < $b") ++
      action.fold(Fragment.empty)(x => fr"and a.action like ${Sql.likeEscape(x) + "%"}") ++
      fr"order by a.id desc limit $limit")
      .query[(Long, Instant, Option[UUID], Option[String], Option[String], String, String, Option[String], Json)]
      .map(r =>
        AuditDto(
          r._1,
          r._2,
          (r._3, r._4).mapN((i, n) => UserBrief(i, n, r._5)),
          r._6,
          r._7,
          r._8,
          r._9
        )
      )
      .to[List]
  )

  def purgeAudit(days: Int): IO[Int] =
    tx(sql"delete from audit_log where at < now() - make_interval(days => $days)".update.run)
}
