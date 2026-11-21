package smohua.db

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import io.circe.syntax.*
import sbirds.shared.AppError
import smohua.core.ErrorCode
import smohua.shared.*

import java.time.Instant
import java.util.UUID

final case class FormRow(id: UUID,
                         ownerId: UUID,
                         title: String,
                         description: String,
                         status: String,
                         access: String,
                         oneResponse: Boolean,
                         confirmation: String,
                         questionsJson: Json,
                         notifyJson: Json,
                         openFrom: Option[Instant],
                         openUntil: Option[Instant],
                         appearanceJson: Json,
                         createdAt: Instant,
                         updatedAt: Instant) {

  /** 読めない要素は飛ばす（古い形のデータが残っていても画面を壊さない）。 */
  def questions: List[QuestionDto] = questionsJson.asArray.fold(Nil)(_.toList.flatMap(_.as[QuestionDto].toOption))
  def notice: FormNotifyDto = notifyJson.as[FormNotifyDto].getOrElse(FormNotifyDto())
  def appearance: FormAppearanceDto = appearanceJson.as[FormAppearanceDto].getOrElse(FormAppearanceDto())
  def schedule: FormScheduleDto = FormScheduleDto(openFrom, openUntil)

  /** 回答する人から見た段階（FormPhase）。受付期間は「受付中」のときだけ効く。 */
  def phase(now: Instant): String = status match {
    case FormStatus.Draft => FormPhase.Draft
    case FormStatus.Closed => FormPhase.Closed
    case _ if openFrom.exists(now.isBefore) => FormPhase.Before
    case _ if openUntil.exists(u => !now.isBefore(u)) => FormPhase.After
    case _ => FormPhase.Open
  }

  /** いま回答を受け付けているか。 */
  def isOpen: Boolean = phase(Instant.now()) == FormPhase.Open
}

final case class ResponseRow(id: UUID, seq: Long, formId: UUID, userId: Option[UUID], answers: Json, createdAt: Instant)

/** 兄弟のサービスへ送る通知（送信待ち）。宛先はユーザー（userId）か、メールアドレス（address。回答の控え）。 */
final case class OutboxRow(id: UUID,
                           userId: Option[UUID],
                           address: Option[String],
                           provider: String,
                           payload: Json,
                           attempts: Int
) // provider: mail / stui

/** フォーム・回答・画面への知らせ・画面内の通知・兄弟のサービスへの通知の送信待ち。 */
final class FormStore(xa: Transactor[IO]) extends TxStore(xa) {

  private val formCols =
    fr"f.id, f.owner_id, f.title, f.description, f.status, f.access, f.one_response, f.confirmation, f.questions, f.notify, f.open_from, f.open_until, f.appearance, f.created_at, f.updated_at"

  private def event(formId: UUID, kind: String): ConnectionIO[Unit] =
    sql"insert into form_event (form_id, kind) values ($formId, $kind)".update.run.void

  // ---------------------------------------------------------------- フォーム

  def create(owner: UUID, title: String, description: String, questions: List[QuestionDto]): IO[FormRow] = tx {
    val id = UUID.randomUUID()
    sql"""insert into form (id, owner_id, title, description, questions, notify)
          values ($id, $owner, $title, $description, ${questions.asJson}, ${FormNotifyDto().asJson})""".update.run *>
      event(id, "form.created") *> findC(id).map(_.get)
  }

  def find(id: UUID): IO[Option[FormRow]] = tx(findC(id))

  private def findC(id: UUID): ConnectionIO[Option[FormRow]] =
    (fr"select" ++ formCols ++ fr"from form f where f.id = $id and f.deleted_at is null").query[FormRow].option

  /** いくつかのフォーム（削除したもの・無いものは入らない。並びは不定）。 */
  def findMany(ids: List[UUID]): IO[List[FormRow]] =
    if ids.isEmpty then IO.pure(Nil)
    else
      tx((fr"select" ++ formCols ++ fr"from form f where f.id = any(${ids.toArray}) and f.deleted_at is null").query[
        FormRow
      ].to[List])

  def editors(id: UUID): IO[List[UserBrief]] = tx(
    sql"""select u.id, u.name, u.avatar_url, false from form_editor e join app_user u on u.id = e.user_id
          where e.form_id = $id order by u.name""".query[UserBrief].to[List]
  )

  def isEditor(id: UUID, userId: UUID): IO[Boolean] =
    tx(sql"select exists(select 1 from form_editor where form_id = $id and user_id = $userId)".query[Boolean].unique)

  /** ids のうち、userId が編集できる人に入っているフォーム。 */
  def editableAmong(ids: List[UUID], userId: UUID): IO[Set[UUID]] =
    if ids.isEmpty then IO.pure(Set.empty)
    else
      tx(sql"select form_id from form_editor where form_id = any(${ids.toArray}) and user_id = $userId".query[
        UUID
      ].to[List]).map(_.toSet)

  /** 回答の数と、最後の回答の日時（まとめて。回答のないフォームは入らない）。 */
  def statsMany(ids: List[UUID]): IO[Map[UUID, (Int, Option[Instant])]] =
    if ids.isEmpty then IO.pure(Map.empty)
    else
      tx(sql"""select form_id, count(*)::int, max(created_at) from form_response where form_id = any(${ids.toArray}) group by form_id"""
        .query[(UUID, Int, Option[Instant])].to[List]).map(_.map((id, n, last) => id -> (n, last)).toMap)

  /** 回答の数と、最後の回答の日時。 */
  def stats(id: UUID): IO[(Int, Option[Instant])] =
    tx(sql"select count(*)::int, max(created_at) from form_response where form_id = $id".query[(
      Int,
      Option[Instant]
    )].unique)

  /** 自分が持ち主か編集できるフォーム（all なら全部。管理者用）。q は題名で絞る。 */
  def list(userId: UUID, all: Boolean, q: Option[String], limit: Int): IO[List[FormSummaryDto]] = tx {
    val where = List(
      Some(fr"f.deleted_at is null"),
      Option.when(!all)(
        fr"(f.owner_id = $userId or exists(select 1 from form_editor e where e.form_id = f.id and e.user_id = $userId))"
      ),
      q.map(_.trim).filter(_.nonEmpty).map(s => fr"f.title ilike ${Sql.containing(s)}")
    ).flatten
    (fr"""select f.id, f.title, f.status, f.access, jsonb_array_length(f.questions),
                 (select count(*)::int from form_response r where r.form_id = f.id),
                 (select max(r.created_at) from form_response r where r.form_id = f.id),
                 u.id, u.name, u.avatar_url, false, f.updated_at
          from form f join app_user u on u.id = f.owner_id""" ++ Sql.whereAnd(where) ++
      fr"order by f.updated_at desc limit $limit")
      .query[FormSummaryDto].to[List]
  }

  /** 指定した項目だけを変える。editorIds は置き換え（持ち主・無効なユーザーは入れない）。 */
  def update(id: UUID,
             title: Option[String],
             description: Option[String],
             status: Option[String],
             access: Option[String],
             oneResponse: Option[Boolean],
             confirmation: Option[String],
             questions: Option[List[QuestionDto]],
             notice: Option[FormNotifyDto],
             editorIds: Option[List[UUID]],
             schedule: Option[FormScheduleDto] = None,
             appearance: Option[FormAppearanceDto] = None): IO[Option[FormRow]] = tx {
    val sets = List(
      title.map(v => fr"title = $v"),
      description.map(v => fr"description = $v"),
      status.map(v => fr"status = $v"),
      access.map(v => fr"access = $v"),
      oneResponse.map(v => fr"one_response = $v"),
      confirmation.map(v => fr"confirmation = $v"),
      questions.map(v => fr"questions = ${v.asJson}"),
      notice.map(v => fr"notify = ${v.asJson}"),
      schedule.map(v => fr"open_from = ${v.from}, open_until = ${v.until}"),
      appearance.map(v => fr"appearance = ${v.asJson}"),
      Some(fr"updated_at = now()")
    ).flatten
    for {
      n <- (fr"update form" ++ Sql.set(sets) ++ fr"where id = $id and deleted_at is null").update.run
      _ <- editorIds.filter(_ => n > 0).traverse_(ids =>
        sql"delete from form_editor where form_id = $id".update.run *>
          sql"""insert into form_editor (form_id, user_id)
                     select $id, u.id from app_user u join form f on f.id = $id
                     where u.id = any(${ids.distinct.toArray}) and not u.disabled and u.id <> f.owner_id""".update.run
      )
      _ <- if n > 0 then event(id, "form.updated") else ().pure[ConnectionIO]
      f <- findC(id)
    } yield f
  }

  /**
   * いま回答を受け付けているか（状態が受付中で、受付期間の中）を見比べ、前と変わったフォームの行と新しい値を返す（受付の開始・締め切りの知らせ）。
   * 前の値は form.accepting に置くので、複数のノードが同時に見ても、変わったことを知るのは1つだけ。id を渡せばそのフォームだけを見る。
   */
  def syncAccepting(id: Option[UUID]): IO[List[(FormRow, Boolean)]] = tx {
    val now =
      fr"(f.status = 'open' and (f.open_from is null or f.open_from <= now()) and (f.open_until is null or now() < f.open_until))"
    (fr"update form f set accepting =" ++ now ++
      fr"where f.deleted_at is null and f.accepting is distinct from" ++ now ++
      id.fold(Fragment.empty)(i => fr"and f.id = $i") ++
      fr"returning" ++ formCols ++ fr", f.accepting").query[(FormRow, Boolean)].to[List]
  }

  def delete(id: UUID): IO[Boolean] = tx(
    sql"update form set deleted_at = now(), status = 'closed' where id = $id and deleted_at is null".update.run.flatMap(
      n => (if n > 0 then event(id, "form.deleted") else ().pure[ConnectionIO]).as(n > 0)
    )
  )

  /** 削除してから days 日たったフォームを、回答ごと消す。 */
  def purgeDeleted(days: Int): IO[Int] =
    tx(sql"delete from form where deleted_at < now() - make_interval(days => $days)".update.run)

  // ---------------------------------------------------------------- 回答

  /**
   * 回答を保存する。受付中かどうか・1人1回かどうかを、保存と同じトランザクションで確かめる
   * （複数のノードに同時に届いても、1人1回のフォームに2つ入らないよう、フォームと人の組でロックを取る）。
   */
  def submit(formId: UUID, userId: Option[UUID], answers: Map[String, Json]): IO[Either[AppError, ResponseRow]] = tx {
    findC(formId).flatMap {
      case None => fail(AppError(ErrorCode.FormNotFound))
      case Some(f) if !f.isOpen => fail(AppError(ErrorCode.FormNotAccepting))
      case Some(f) =>
        val once = f.oneResponse && userId.isDefined
        val lock = if once then
          sql"select pg_advisory_xact_lock(hashtextextended(${s"$formId:${userId.get}"}, 0))".query[Unit].unique
        else ().pure[ConnectionIO]
        val dup = if once then
          sql"select exists(select 1 from form_response where form_id = $formId and user_id = $userId)".query[
            Boolean
          ].unique
        else false.pure[ConnectionIO]
        (lock *> dup).flatMap {
          case true => fail(AppError(ErrorCode.ResponseAlreadySubmitted))
          case false =>
            val id = UUID.randomUUID()
            sql"""insert into form_response (id, form_id, user_id, answers) values ($id, $formId, $userId, ${answers.asJson})
                  returning id, seq, form_id, user_id, answers, created_at""".query[ResponseRow].unique.flatTap(_ =>
              event(formId, "response.created")
            )
              .map(_.asRight[AppError])
        }
    }
  }

  def answered(formId: UUID, userId: UUID): IO[Boolean] = tx(
    sql"select exists(select 1 from form_response where form_id = $formId and user_id = $userId)".query[Boolean].unique
  )

  /** 回答の行（cond は seq の条件、order は並び）。回答した人の名前・アイコンも付ける。 */
  private def responseRows(formId: UUID, cond: Fragment, order: Fragment, limit: Int): ConnectionIO[List[ResponseDto]] =
    (fr"""select r.id, r.seq, u.id, u.name, u.avatar_url, r.answers, r.created_at
          from form_response r left join app_user u on u.id = r.user_id where r.form_id = $formId""" ++ cond ++ order ++
      fr"limit $limit")
      .query[(UUID, Long, Option[UUID], Option[String], Option[String], Json, Instant)]
      .map(r =>
        ResponseDto(
          r._1,
          r._2,
          (r._3, r._4).mapN((i, n) => UserBrief(i, n, r._5)),
          r._6.as[Map[String, Json]].getOrElse(Map.empty),
          r._7
        )
      )
      .to[List]

  /** 新しい順。before（seq）より前から limit 件。 */
  def responses(formId: UUID, before: Option[Long], limit: Int): IO[ResponsePageDto] = tx(
    for {
      total <- sql"select count(*)::int from form_response where form_id = $formId".query[Int].unique
      rows <- responseRows(formId, before.fold(Fragment.empty)(b => fr"and r.seq < $b"), fr"order by r.seq desc", limit)
    } yield ResponsePageDto(total, rows)
  )

  /** 古い順。after（seq）より後から limit 件（CSV 用。件数は数えない）。 */
  def responsesAfter(formId: UUID, after: Option[Long], limit: Int): IO[List[ResponseDto]] =
    tx(responseRows(formId, after.fold(Fragment.empty)(a => fr"and r.seq > $a"), fr"order by r.seq", limit))

  /** 集計用: 新しい順に回答の中身だけ（多すぎるフォームは新しい limit 件で集計する）。 */
  def answers(formId: UUID, limit: Int): IO[List[Map[String, Json]]] = tx(
    sql"select answers from form_response where form_id = $formId order by seq desc limit $limit".query[Json].to[List]
  ).map(_.map(_.as[Map[String, Json]].getOrElse(Map.empty)))

  def deleteResponse(formId: UUID, id: UUID): IO[Boolean] =
    tx(sql"delete from form_response where id = $id and form_id = $formId".update.run.flatMap(n =>
      (if n > 0 then event(formId, "response.deleted") else ().pure[ConnectionIO]).as(n > 0)
    ))

  // ---------------------------------------------------------------- 画面への知らせ（form_event）

  def latestEvent: IO[Long] = tx(sql"select coalesce(max(id), 0) from form_event".query[Long].unique)

  /** last より後の変更（番号, フォーム, 種類）。 */
  def eventsAfter(last: Long): IO[List[(Long, UUID, String)]] =
    tx(sql"select id, form_id, kind from form_event where id > $last order by id limit 1000".query[(
      Long,
      UUID,
      String
    )].to[List])

  /** そのフォームの変更を受け取る人（持ち主と編集できる人）。 */
  def watchers(formId: UUID): IO[Set[UUID]] = watchersOf(List(formId)).map(_.getOrElse(formId, Set.empty))

  /** いくつかのフォームの、変更を受け取る人（まとめて読む）。 */
  def watchersOf(formIds: List[UUID]): IO[Map[UUID, Set[UUID]]] =
    if formIds.isEmpty then IO.pure(Map.empty)
    else
      tx(sql"""select id, owner_id from form where id = any(${formIds.toArray})
                  union select form_id, user_id from form_editor where form_id = any(${formIds.toArray})""".query[(
        UUID,
        UUID
      )].to[List])
        .map(_.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap)

  def purgeEvents(days: Int): IO[Int] =
    tx(sql"delete from form_event where at < now() - make_interval(days => $days)".update.run)

  // ---------------------------------------------------------------- 画面内の通知

  def notify(userId: UUID, kind: String, formId: Option[UUID], title: String, body: String, link: String): IO[UUID] =
    tx {
      val id = UUID.randomUUID()
      sql"""insert into notification (id, user_id, kind, form_id, title, body, link)
          values ($id, $userId, $kind, $formId, $title, $body, $link)""".update.run.as(id)
    }

  def notifications(userId: UUID, unreadOnly: Boolean, limit: Int): IO[NotificationListDto] = tx(
    for {
      items <-
      (fr"select id, kind, form_id, title, body, link, at, read_at is not null from notification where user_id = $userId" ++
        (if unreadOnly then fr"and read_at is null" else Fragment.empty) ++ fr"order by at desc limit $limit").query[
        NotificationDto
      ].to[List]
      unread <-
        sql"select count(*)::int from notification where user_id = $userId and read_at is null".query[Int].unique
    } yield NotificationListDto(items, unread)
  )

  def unread(userId: UUID): IO[Int] =
    tx(sql"select count(*)::int from notification where user_id = $userId and read_at is null".query[Int].unique)

  /** ids が空ならすべて既読にする。 */
  def markRead(userId: UUID, ids: List[UUID]): IO[Unit] = tx(
    (fr"update notification set read_at = now() where user_id = $userId and read_at is null" ++
      (if ids.isEmpty then Fragment.empty else fr"and id = any(${ids.toArray})")).update.run.void
  )

  /** フォームの回答を開いたら、そのフォームの通知を既読にする。 */
  def markFormRead(userId: UUID, formId: UUID): IO[Unit] = tx(
    sql"update notification set read_at = now() where user_id = $userId and form_id = $formId and read_at is null".update.run.void
  )

  /** since より後に通知を受けた人（画面への知らせ）。 */
  def notifiedSince(since: Instant): IO[List[(UUID, Instant)]] =
    tx(sql"select user_id, max(at) from notification where at > $since group by user_id".query[(
      UUID,
      Instant
    )].to[List])

  def purgeNotifications(days: Int): IO[Int] =
    tx(sql"delete from notification where at < now() - make_interval(days => $days)".update.run)

  // ---------------------------------------------------------------- 兄弟のサービスへの通知（送信待ち）

  def enqueue(userId: UUID, provider: String, payload: Json): IO[Unit] = tx(
    sql"insert into notify_outbox (id, user_id, provider, payload) values (${UUID.randomUUID()}, $userId, $provider, $payload)".update.run.void
  )

  /**
   * ユーザーでない宛先（メールアドレス）へのメールを積む。同じアドレスへ1時間に perHour 通を超えるなら積まない（false）
   * （匿名のフォームから、知らない人へメールを送り付けるのに使われないように）。
   */
  def enqueueMail(address: String, payload: Json, perHour: Int): IO[Boolean] = tx(
    sql"select pg_advisory_xact_lock(hashtext(${"mail:" + address.toLowerCase}))".query[Unit].unique *>
      sql"""select count(*) from notify_outbox
            where lower(address) = ${address.toLowerCase} and created_at > now() - interval '1 hour'""".query[
        Int
      ].unique.flatMap(n =>
        if n >= perHour then false.pure[ConnectionIO]
        else
          sql"""insert into notify_outbox (id, address, provider, payload)
                values (${UUID.randomUUID()}, $address, 'mail', $payload)""".update.run.as(true)
      )
  )

  /** 送る分を取り出し（SKIP LOCKED で複数ノードの重複を防ぐ）、send の結果を記録する。5回失敗したらあきらめる。 */
  def processOutbox(batch: Int)(send: OutboxRow => IO[OutboxResult]): IO[Int] =
    processEach(
      sql"""select id, user_id, address, provider, payload, attempts from notify_outbox
            where sent_at is null and not skipped and not_before <= now() order by not_before limit $batch for update skip locked""".query[
        OutboxRow
      ].to[List]
    )(m => send(m).handleError(e => OutboxResult.Retry(String.valueOf(e.getMessage).take(300)))) {
      case (m, OutboxResult.Sent) =>
        sql"update notify_outbox set sent_at = now(), attempts = attempts + 1 where id = ${m.id}".update.run
      case (m, OutboxResult.Skip(reason)) =>
        sql"update notify_outbox set skipped = true, last_error = $reason where id = ${m.id}".update.run
      case (m, OutboxResult.Retry(err)) =>
        sql"""update notify_outbox set attempts = attempts + 1, last_error = $err, skipped = ${m.attempts + 1 >= 5},
              not_before = now() + make_interval(secs => ${Outbox.backoffSeconds(
            m.attempts
          )}) where id = ${m.id}""".update.run
    }

  def purgeOutbox(days: Int): IO[Int] = tx(
    sql"delete from notify_outbox where (sent_at is not null or skipped) and created_at < now() - make_interval(days => $days)".update.run
  )

  def briefs(ids: List[UUID]): IO[List[UserBrief]] = tx(
    sql"select id, name, avatar_url, false from app_user where id = any(${ids.toArray})".query[UserBrief].to[List]
  )
}
