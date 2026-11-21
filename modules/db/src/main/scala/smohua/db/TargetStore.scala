package smohua.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import sbirds.db.{Outbox, OutboxResult, TxStore}
import smohua.shared.*

import java.time.Instant
import java.util.UUID

/** フォームの通知先の行（secretEnc は webhook の署名の鍵を暗号化したもの）。 */
final case class FormTargetRow(id: UUID,
                               formId: UUID,
                               name: String,
                               kind: String,
                               url: Option[String],
                               address: Option[String],
                               secretEnc: Option[String],
                               events: List[String],
                               enabled: Boolean,
                               lastError: Option[String],
                               createdAt: Instant)

/** 送信待ちの通知（送るときの通知先を付けて取り出す）。 */
final case class FormDeliveryRow(id: UUID, attempts: Int, payload: Json, target: FormTargetRow)

/** フォームの通知先（受信 Webhook・署名付きの Webhook・メールアドレス）と、その送信待ちの表。 */
final class TargetStore(xa: Transactor[IO]) extends TxStore(xa) {

  /** 1つのフォームの通知先の数の上限。 */
  val MaxTargets = 20

  /** 送り直す回数の上限（それを超えたら送るのをやめる）。 */
  val MaxAttempts = 8

  private val cols =
    fr"t.id, t.form_id, t.name, t.kind, t.url, t.address, t.secret_enc, t.events, t.enabled, t.last_error, t.created_at"

  private val select = fr"select" ++ cols ++ fr"from form_target t"

  def targets(formId: UUID): IO[List[FormTargetRow]] =
    tx((select ++ fr"where t.form_id = $formId order by t.created_at").query[FormTargetRow].to[List])

  def target(formId: UUID, id: UUID): IO[Option[FormTargetRow]] =
    tx((select ++ fr"where t.id = $id and t.form_id = $formId").query[FormTargetRow].option)

  def count(formId: UUID): IO[Int] = tx(sql"select count(*) from form_target where form_id = $formId".query[Int].unique)

  def insert(t: FormTargetRow): IO[Unit] = tx(
    sql"""insert into form_target (id, form_id, name, kind, url, address, secret_enc, events, enabled)
          values (${t.id}, ${t.formId}, ${t.name}, ${t.kind}, ${t.url}, ${t.address}, ${t.secretEnc}, ${t.events}, ${t.enabled})""".update.run.void
  )

  /** 名前・送り先・きっかけ・有効を変える（種類と署名の鍵は変えない）。前の失敗の理由は消す。 */
  def update(formId: UUID, id: UUID, r: FormTargetReq): IO[Boolean] = tx(
    sql"""update form_target set name = ${r.name}, url = ${r.url}, address = ${r.address}, events = ${r.events},
            enabled = ${r.enabled}, last_error = null
          where id = $id and form_id = $formId""".update.run.map(_ > 0)
  )

  def delete(formId: UUID, id: UUID): IO[Boolean] =
    tx(sql"delete from form_target where id = $id and form_id = $formId".update.run.map(_ > 0))

  /** イベントを、それを選んだ有効な通知先すべての送信待ちに積む。 */
  def enqueueEvent(formId: UUID, event: String, payload: Json): IO[Int] = tx(
    sql"""insert into form_delivery (id, target_id, payload)
          select gen_random_uuid(), t.id, $payload from form_target t
          where t.form_id = $formId and t.enabled and $event = any(t.events)""".update.run
  )

  /**
   * 送る時刻が来たものを、ほかのノードと取り合わずに（SKIP LOCKED）送る。送れなかった理由は通知先にも残す（画面に出す）。
   * Skip（送り直しても無駄）なら、その場で送るのをやめる。
   */
  def processDeliveries(batch: Int)(send: FormDeliveryRow => IO[(OutboxResult, Option[Int])]): IO[Int] =
    processEach(
      (fr"select d.id, d.attempts, d.payload," ++ cols ++
        fr"""from form_delivery d join form_target t on t.id = d.target_id
             where d.done_at is null and not d.failed and d.not_before <= now()
             order by d.not_before limit $batch for update of d skip locked""").query[FormDeliveryRow].to[List]
    )(d => send(d).handleError(e => (OutboxResult.Retry(e.getClass.getSimpleName), None))) {
      case (d, (OutboxResult.Sent, code)) =>
        sql"update form_delivery set done_at = now(), attempts = attempts + 1, status_code = $code where id = ${d.id}".update.run <*
          sql"update form_target set last_error = null where id = ${d.target.id} and last_error is not null".update.run
      case (d, (OutboxResult.Skip(r), code)) =>
        sql"update form_delivery set failed = true, last_error = $r, status_code = $code where id = ${d.id}".update.run <*
          sql"update form_target set last_error = $r where id = ${d.target.id}".update.run
      case (d, (OutboxResult.Retry(err), code)) =>
        val giveUp = d.attempts + 1 >= MaxAttempts
        sql"""update form_delivery set attempts = attempts + 1, last_error = $err, status_code = $code, failed = $giveUp,
                not_before = now() + make_interval(secs => ${Outbox.backoffSeconds(
            d.attempts
          )}) where id = ${d.id}""".update.run <*
          sql"update form_target set last_error = $err where id = ${d.target.id}".update.run.whenA(giveUp)
    }

  /** 送り終えた・やめた送信待ちを消す（days 日より前のもの）。 */
  def purge(days: Int): IO[Int] = tx(
    sql"delete from form_delivery where created_at < now() - make_interval(days => $days) and (done_at is not null or failed)".update.run
  )
}
