package smohua.db

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import smohua.core.*
import smohua.shared.*

import java.util.UUID

/** DB を使うテストの共通部分。TEST_DATABASE_URL がなければ飛ばす（例: jdbc:postgresql://localhost:54398/smohua_test）。 */
trait DbSuite extends CatsEffectSuite {

  private val cfg: Option[DbConfig] = sys.env.get("TEST_DATABASE_URL").map(url =>
    DbConfig(
      url,
      sys.env.getOrElse("TEST_DATABASE_USER", "smohua"),
      sys.env.getOrElse("TEST_DATABASE_PASSWORD", "smohua")
    )
  )

  private val xaFixture = ResourceSuiteLocalFixture(
    "xa",
    cfg.fold(Resource.eval(IO.raiseError[doobie.Transactor[IO]](new RuntimeException("no db"))))(c =>
      Resource.eval(Database.migrate(c)) *> Database.transactor(c)
    )
  )

  override def munitFixtures = if cfg.isDefined then List(xaFixture) else Nil

  protected def withDb(
    name: String
  )(
    body: doobie.Transactor[IO] => IO[Unit]
  ): Unit =
    test(name) {
      if cfg.isEmpty then IO(assume(false, "TEST_DATABASE_URL が未設定")) else body(xaFixture())
    }

  protected def newUser(
    users: UserStore,
    name: String,
    avatar: Option[String] = None
  ): IO[User] =
    users.upsert(s"test:${UUID.randomUUID()}", name, Some(s"${UUID.randomUUID()}@example.com"), avatar)
}

class StoreSpec extends DbSuite {

  private val qs = List(
    QuestionDto("name", QuestionKind.Short, "お名前", required = true),
    QuestionDto("food", QuestionKind.Single, "好きな食べ物", options = List("寿司", "カレー"))
  )

  withDb("ユーザー: IdP のアイコンを写し、外されたら外す") { xa =>
    val users = new UserStore(xa)
    val sub = s"test:${UUID.randomUUID()}"
    for {
      a <- users.upsert(sub, "山田", None, Some("https://idp.example/avatars/1.png"))
      _ = assertEquals(a.avatarUrl, Some("https://idp.example/avatars/1.png"))
      b <- users.upsert(sub, "山田 太郎", None, Some("https://idp.example/avatars/2.png"))
      _ = assertEquals((b.id, b.name, b.avatarUrl), (a.id, "山田 太郎", Some("https://idp.example/avatars/2.png")))
      c <- users.upsert(sub, "山田 太郎", None, None)
      _ = assertEquals(c.avatarUrl, None)
    } yield ()
  }

  withDb("フォーム: 作成・変更・一覧・編集できる人・削除") { xa =>
    val users = new UserStore(xa); val forms = new FormStore(xa)
    for {
      a <- newUser(users, "持ち主")
      b <- newUser(users, "編集者")
      f <- forms.create(a.id, "アンケート", "説明", qs)
      _ = assertEquals(
        (f.status, f.access, f.questions, f.notice),
        (FormStatus.Draft, FormAccess.Login, qs, FormNotifyDto())
      )
      g <- forms.update(
        f.id,
        Some("新しい題名"),
        None,
        Some(FormStatus.Open),
        None,
        None,
        None,
        None,
        Some(FormNotifyDto(inApp = false, mail = true, stui = true, stuiChannel = "開発")),
        Some(List(b.id, a.id))
      ).map(_.get)
      _ = assertEquals((g.title, g.status, g.notice.mail, g.notice.stuiChannel), ("新しい題名", FormStatus.Open, true, "開発"))
      es <- forms.editors(f.id)
      _ = assertEquals(es.map(_.id), List(b.id)) // 持ち主は入れない
      _ <- forms.watchers(f.id).map(ws => assertEquals(ws, Set(a.id, b.id)))
      lb <- forms.list(b.id, all = false, None, 50)
      _ = assert(lb.exists(x => x.id == f.id && x.questionCount == 2 && x.owner.id == a.id))
      c <- newUser(users, "ほかの人")
      _ <- forms.list(c.id, all = false, None, 50).map(l => assert(!l.exists(_.id == f.id)))
      _ <- forms.list(c.id, all = true, Some("新しい題"), 500).map(l => assert(l.exists(_.id == f.id)))
      _ <- forms.delete(f.id).map(assert(_))
      _ <- forms.find(f.id).map(x => assert(x.isEmpty))
    } yield ()
  }

  withDb("受付期間: 期間の外では回答できず、見た目の設定は保存して読める") { xa =>
    val users = new UserStore(xa); val forms = new FormStore(xa)
    val now = java.time.Instant.now()
    val ans = Map("name" -> "x".asJson)
    for {
      a <- newUser(users, "持ち主")
      f <- forms.create(a.id, "期間つき", "", qs)
      _ <- forms.update(
        f.id,
        None,
        None,
        Some(FormStatus.Open),
        None,
        None,
        None,
        None,
        None,
        None,
        schedule = Some(FormScheduleDto(Some(now.plusSeconds(3600)), None)),
        appearance = Some(FormAppearanceDto(background = "#ffffff", submitLabel = "申し込む", showBranding = false))
      )
      g <- forms.find(f.id).map(_.get)
      _ = assertEquals(
        (g.phase(now), g.appearance.submitLabel, g.appearance.showBranding),
        (FormPhase.Before, "申し込む", false)
      )
      _ <- forms.submit(f.id, None, ans).map(r => assert(r.isLeft)) // 受付前
      _ <- forms.update(
        f.id,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        schedule = Some(FormScheduleDto(Some(now.minusSeconds(3600)), Some(now.plusSeconds(3600))))
      )
      _ <- forms.submit(f.id, None, ans).map(r => assert(r.isRight)) // 期間の中
      _ <- forms.update(
        f.id,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        schedule = Some(FormScheduleDto(None, Some(now.minusSeconds(1))))
      )
      h <- forms.find(f.id).map(_.get)
      _ = assertEquals(h.phase(java.time.Instant.now()), FormPhase.After)
      _ <- forms.submit(f.id, None, ans).map(r => assert(r.isLeft)) // 期間が終わった
    } yield ()
  }

  withDb("回答: 受付中だけ・1人1回・一覧・集計用の読み出し・削除") { xa =>
    val users = new UserStore(xa); val forms = new FormStore(xa)
    def ans(
      n: String
    ) = Map("name" -> n.asJson, "food" -> "寿司".asJson)
    for {
      a <- newUser(users, "持ち主")
      u <- newUser(users, "回答者", Some("https://idp.example/a.png"))
      f <- forms.create(a.id, "出欠", "", qs)
      closed <- forms.submit(f.id, Some(u.id), ans("x"))
      _ = assert(closed.isLeft) // 下書きには回答できない
      _ <- forms.update(f.id, None, None, Some(FormStatus.Open), None, Some(true), None, None, None, None)
      r1 <- forms.submit(f.id, Some(u.id), ans("一回目")).map(_.toOption.get)
      // 同時に届いても1つだけ
      dups <- List.fill(5)(forms.submit(f.id, Some(u.id), ans("二回目"))).parSequence
      _ = assert(dups.forall(_.isLeft))
      _ <- forms.answered(f.id, u.id).map(assert(_))
      _ <- forms.submit(f.id, None, ans("匿名")).map(r => assert(r.isRight))
      _ <- forms.submit(f.id, None, ans("匿名2")).map(r => assert(r.isRight))
      p <- forms.responses(f.id, None, 2)
      _ = assertEquals((p.total, p.responses.map(r => Answers.text(r.answers("name")))), (3, List("匿名2", "匿名")))
      p2 <- forms.responses(f.id, p.responses.lastOption.map(_.seq), 2)
      _ = assertEquals(
        p2.responses.map(_.respondent.map(x => (x.name, x.avatarUrl))),
        List(Some(("回答者", Some("https://idp.example/a.png"))))
      )
      as <- forms.answers(f.id, 100)
      _ = assertEquals(Forms.summarize(qs, as).find(_.id == "food").get.counts.head, CountDto("寿司", 3))
      st <- forms.stats(f.id)
      _ = assertEquals(st._1, 3)
      _ <- forms.deleteResponse(f.id, r1.id).map(assert(_))
      _ <- forms.deleteResponse(UUID.randomUUID(), r1.id).map(x => assert(!x))
      // 削除した回答の人は、もう一度回答できる
      _ <- forms.submit(f.id, Some(u.id), ans("やり直し")).map(r => assert(r.isRight))
    } yield ()
  }

  withDb("知らせ: 変更履歴・画面内の通知・送信待ち（取り合い・再試行・あきらめ）") { xa =>
    val users = new UserStore(xa); val forms = new FormStore(xa)
    for {
      a <- newUser(users, "持ち主")
      last <- forms.latestEvent
      f <- forms.create(a.id, "知らせ", "", qs)
      _ <- forms.update(f.id, None, None, Some(FormStatus.Open), None, None, None, None, None, None)
      _ <- forms.submit(f.id, None, Map("name" -> "x".asJson))
      evs <- forms.eventsAfter(last).map(_.filter(_._2 == f.id).map(_._3))
      _ = assertEquals(evs, List("form.created", "form.updated", "response.created"))
      t0 <- IO.realTimeInstant.map(_.minusSeconds(5))
      n <- forms.notify(a.id, "response", Some(f.id), "回答が届きました", "本文", Paths.responses(f.id))
      _ <- forms.unread(a.id).map(assertEquals(_, 1))
      _ <- forms.notifiedSince(t0).map(ns => assert(ns.exists(_._1 == a.id)))
      l <- forms.notifications(a.id, unreadOnly = true, 10)
      _ = assertEquals(l.items.map(x => (x.id, x.link, x.read)), List((n, s"/forms/${f.id}/responses", false)))
      _ <- forms.markFormRead(a.id, f.id) *> forms.unread(a.id).map(assertEquals(_, 0))
      // 送信待ち: 自分の分だけを数える（ほかのテストの行が混ざっても壊れないように）
      _ <- forms.enqueue(a.id, "stui", Json.obj("title" -> "t".asJson))
      seen <- Ref.of[IO, Int](0)
      mine = (r: OutboxRow) => r.userId == a.id
      _ <- forms.processOutbox(100)(r =>
        if mine(r) then seen.update(_ + 1).as(OutboxResult.Retry("一時的な失敗")) else IO.pure(OutboxResult.Skip("test"))
      )
      _ <- forms.processOutbox(100)(r =>
        if mine(r) then seen.update(_ + 1).as(OutboxResult.Sent) else IO.pure(OutboxResult.Skip("test"))
      )
      _ <- seen.get.map(assertEquals(_, 1)) // 失敗した直後は、間を空けるまで取り出さない
    } yield ()
  }

  withDb("排他: 同じ名前のリースは1ノードだけが取れる") { xa =>
    import scala.concurrent.duration.*
    val name = s"test-${UUID.randomUUID()}"
    for {
      a <- new JobLock(xa, "node-a").acquire(name, 1.minute)
      b <- new JobLock(xa, "node-b").acquire(name, 1.minute)
      a2 <- new JobLock(xa, "node-a").acquire(name, 1.minute)
    } yield assertEquals((a, b, a2), (true, false, true))
  }
}
