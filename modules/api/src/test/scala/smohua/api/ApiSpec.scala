package smohua.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import smohua.auth.{ApiKeys, Crypto, SecretBox}
import smohua.core.User
import smohua.db.*
import smohua.notify.{Mail, Mailer}
import smohua.shared.*

import java.util.UUID

/** REST API・MCP・通知を、ルートを通して確かめる（DB が要る）。stui とメールは偽物に差し替える。 */
class ApiSpec extends DbSuite:

  private val cfg = ApiConfig(Set.empty, cookieSecure = false, "https://smohua.example.com", mail = true,
    stui = Some(LinkService("stui", "https://stui.example.com", "smohua", "secret-secret-secret")))

  private final case class Env(app: HttpApp[IO], users: UserStore, forms: FormStore, tokens: IntegrationTokenStore, box: SecretBox,
      notifier: NotifyService, stuiCalls: Ref[IO, List[(String, Json)]], mails: Ref[IO, List[Mail]])

  private def env(xa: doobie.Transactor[IO]): IO[Env] =
    (Ref.of[IO, List[(String, Json)]](Nil), Ref.of[IO, List[Mail]](Nil), EventFeed(new FormStore(xa))).mapN { (calls, mails, feed) =>
      val users = new UserStore(xa); val forms = new FormStore(xa); val activity = new ActivityStore(xa); val tokens = new IntegrationTokenStore(xa)
      val box = new SecretBox("test-secret-0123456789")
      // 偽の stui: トークンが "revoked" なら 401
      val stui = Client.fromHttpApp(HttpRoutes.of[IO] {
        case req @ POST -> Root / "api" / "apps" / "notify" =>
          val token = req.headers.get[Authorization].collect { case Authorization(Credentials.Token(_, t)) => t }.getOrElse("")
          if token == "revoked" then IO.pure(Response[IO](Status.Unauthorized)) else req.as[Json].flatMap(j => calls.update(_ :+ (token -> j))) *> Created(Json.obj())
      }.orNotFound)
      val mailer = new Mailer { def send(m: Mail): IO[Unit] = mails.update(_ :+ m) }
      val sess = new SessionAuth(users, cfg)
      val notifier = new NotifyService(forms, users, tokens, box, stui, Some(mailer), None, cfg)
      val svc = new FormService(forms, notifier, activity, cfg)
      val rest = new UserRoutes(users, activity, sess).routes <+> new FormRoutes(forms, svc, sess).routes <+> new StreamRoutes(feed, sess).routes
      Env((rest <+> new McpRoutes(rest, sess, cfg.publicUrl).routes).orNotFound, users, forms, tokens, box, notifier, calls, mails)
    }

  private def key(e: Env, u: User, scopes: List[String] = List("read", "write")): IO[String] =
    val (k, prefix) = ApiKeys.generate()
    e.users.createKey(u.id, "test", prefix, Crypto.sha256(k), scopes, None).as(k)

  private def call(e: Env, method: Method, path: String, key: Option[String], body: Option[Json] = None): IO[(Status, Json)] =
    val r0 = Request[IO](method, Uri.unsafeFromString(path))
    val r1 = key.fold(r0)(k => r0.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, k))))
    e.app.run(body.fold(r1)(r1.withEntity(_))).flatMap(r => r.bodyText.compile.string.map(s => r.status -> io.circe.parser.parse(s).getOrElse(Json.fromString(s))))

  private val questions = Json.arr(
    Json.obj("id" -> "name".asJson, "kind" -> "short".asJson, "label" -> "お名前".asJson, "required" -> true.asJson),
    Json.obj("id" -> "day".asJson, "kind" -> "multi".asJson, "label" -> "参加できる日".asJson, "options" -> List("月", "火").asJson))

  private def id(j: Json): String = j.hcursor.get[String]("id").getOrElse(fail(s"id がありません: ${j.noSpaces}"))

  /** 送信待ちを処理する（自分のテストの分が送られるまで何度か）。 */
  private def drain(e: Env): IO[Unit] = e.forms.processOutbox(1000)(_ => IO.pure(OutboxResult.Skip("前のテストの残り"))).void

  withDb("フォームを作る → 公開する → 回答する → 一覧・集計・CSV で見る") { xa =>
    for
      e <- env(xa)
      owner <- newUser(e.users, "持ち主"); other <- newUser(e.users, "回答者")
      ko <- key(e, owner); ku <- key(e, other); kr <- key(e, owner, List("read"))
      none <- call(e, Method.GET, "/api/forms", None)
      _ = assertEquals(none._1, Status.Unauthorized)
      c <- call(e, Method.POST, "/api/forms", Some(ko), Some(Json.obj("title" -> "懇親会の出欠".asJson, "questions" -> questions)))
      _ = assertEquals(c._1, Status.Created)
      fid = id(c._2)
      // 下書きは、ほかの人には見えない・回答できない
      _ <- call(e, Method.GET, s"/api/public/forms/$fid", Some(ku)).map(r => assertEquals(r._1, Status.NotFound))
      _ <- call(e, Method.GET, s"/api/public/forms/$fid", Some(ko)).map(r => assertEquals(r._1, Status.Ok))
      // 参照専用のキーでは変えられない
      _ <- call(e, Method.PATCH, s"/api/forms/$fid", Some(kr), Some(Json.obj("status" -> "open".asJson))).map(r => assertEquals(r._1, Status.Forbidden))
      // 匿名のフォームに「1人1回」は付けられない
      bad <- call(e, Method.PATCH, s"/api/forms/$fid", Some(ko), Some(Json.obj("access" -> "anyone".asJson, "oneResponse" -> true.asJson)))
      _ = assertEquals(bad._1, Status.BadRequest)
      o <- call(e, Method.PATCH, s"/api/forms/$fid", Some(ko), Some(Json.obj("status" -> "open".asJson, "oneResponse" -> true.asJson, "confirmation" -> "ありがとうございました".asJson)))
      _ = assertEquals((o._1, o._2.hcursor.get[String]("status")), (Status.Ok, Right("open")))
      // ログインが要るフォーム: ログインしていなければ質問を見せず、回答も受けない
      p0 <- call(e, Method.GET, s"/api/public/forms/$fid", None)
      _ = assertEquals((p0._2.hcursor.get[Boolean]("needLogin"), p0._2.hcursor.get[List[Json]]("questions").map(_.size)), (Right(true), Right(0)))
      _ <- call(e, Method.POST, s"/api/public/forms/$fid/responses", None, Some(Json.obj("answers" -> Json.obj("name" -> "x".asJson)))).map(r => assertEquals(r._1, Status.Unauthorized))
      // 必須の質問が空・選択肢にない値
      inv <- call(e, Method.POST, s"/api/public/forms/$fid/responses", Some(ku), Some(Json.obj("answers" -> Json.obj("day" -> List("日").asJson))))
      _ = assertEquals(inv._1, Status.BadRequest)
      ok <- call(e, Method.POST, s"/api/public/forms/$fid/responses", Some(ku), Some(Json.obj("answers" -> Json.obj("name" -> "佐藤".asJson, "day" -> List("火", "月").asJson))))
      _ = assertEquals((ok._1, ok._2.hcursor.get[String]("confirmation")), (Status.Created, Right("ありがとうございました")))
      again <- call(e, Method.POST, s"/api/public/forms/$fid/responses", Some(ku), Some(Json.obj("answers" -> Json.obj("name" -> "佐藤".asJson))))
      _ = assertEquals(again._1, Status.Conflict)
      _ <- call(e, Method.GET, s"/api/public/forms/$fid", Some(ku)).map(r => assertEquals(r._2.hcursor.get[Boolean]("answered"), Right(true)))
      // 回答は、持ち主だけが見られる
      _ <- call(e, Method.GET, s"/api/forms/$fid/responses", Some(ku)).map(r => assertEquals(r._1, Status.NotFound))
      rs <- call(e, Method.GET, s"/api/forms/$fid/responses", Some(kr))
      _ = assertEquals(rs._2.hcursor.get[Int]("total"), Right(1))
      r0 = rs._2.hcursor.downField("responses").downArray
      _ = assertEquals((r0.downField("respondent").get[String]("name"), r0.downField("answers").get[List[String]]("day")), (Right("回答者"), Right(List("月", "火"))))
      sm <- call(e, Method.GET, s"/api/forms/$fid/summary", Some(ko))
      _ = assertEquals(sm._2.as[FormSummaryStatsDto].map(s => (s.total, s.questions.find(_.id == "day").map(_.counts))), Right((1, Some(List(CountDto("月", 1), CountDto("火", 1))))))
      // CSV は BOM 付きの UTF-8（Excel で開けるように）
      csv <- e.app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/forms/$fid/responses.csv")).putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, ko))))
               .flatMap(_.body.compile.to(Array)).map(new String(_, "UTF-8"))
      _ = assert(csv.startsWith("\uFEFF番号,回答日時,回答者,お名前,参加できる日\r\n") && csv.contains(",回答者,佐藤,月、火\r\n"), csv)
      // 連携サービス向け: 見られるものだけ
      lk <- call(e, Method.POST, "/api/forms/lookup", Some(kr), Some(Json.obj("ids" -> List(fid, UUID.randomUUID().toString).asJson)))
      _ = assertEquals(lk._2.hcursor.downField("forms").as[List[Json]].map(_.map(f => (id(f), f.hcursor.get[Int]("responses").getOrElse(-1)))), Right(List((fid, 1))))
      _ <- call(e, Method.POST, "/api/forms/lookup", Some(ku), Some(Json.obj("ids" -> List(fid).asJson))).map(r => assertEquals(r._2.hcursor.downField("forms").as[List[Json]], Right(Nil)))
      // 締め切ったら回答できない
      _ <- call(e, Method.PATCH, s"/api/forms/$fid", Some(ko), Some(Json.obj("status" -> "closed".asJson, "oneResponse" -> false.asJson)))
      _ <- call(e, Method.POST, s"/api/public/forms/$fid/responses", Some(ku), Some(Json.obj("answers" -> Json.obj("name" -> "佐藤".asJson)))).map(r => assertEquals(r._1, Status.Conflict))
      _ <- call(e, Method.DELETE, s"/api/forms/$fid", Some(ko)).map(r => assertEquals(r._1, Status.NoContent))
    yield ()
  }

  withDb("通知: 持ち主と編集できる人に、画面内の通知を出す") { xa =>
    for
      e <- env(xa)
      _ <- drain(e)
      owner <- newUser(e.users, "持ち主"); editor <- newUser(e.users, "編集者")
      ko <- key(e, owner)
      _ <- e.tokens.saveToken(owner.id, "stui", e.box.encrypt("owner-token"))
      _ <- e.tokens.saveToken(editor.id, "stui", e.box.encrypt("revoked"))
      c <- call(e, Method.POST, "/api/forms", Some(ko), Some(Json.obj("title" -> "問い合わせ".asJson, "questions" -> questions)))
      fid = id(c._2)
      _ <- call(e, Method.PATCH, s"/api/forms/$fid", Some(ko), Some(Json.obj("status" -> "open".asJson, "access" -> "anyone".asJson, "editorIds" -> List(editor.id).asJson,
             "notice" -> FormNotifyDto(inApp = true, mail = true, stui = true).asJson))).map(r => assertEquals(r._1, Status.Ok))
      f <- e.forms.find(UUID.fromString(fid)).map(_.get)
      // 匿名の回答（だれが答えたかは記録しない）
      r <- e.forms.submit(f.id, None, Map("name" -> "田中".asJson)).map(_.toOption.get)
      _ <- e.notifier.responseArrived(f, r, None)
      _ <- List(owner, editor).traverse_(u => e.forms.notifications(u.id, unreadOnly = true, 10).map(l =>
             assertEquals(l.items.map(n => (n.title, n.body, n.link)), List(("「問い合わせ」に回答が届きました", "お名前: 田中", s"/forms/$fid/responses")))))
      _ <- drain(e)
    yield ()
  }

  withDb("通知のワーカー: メールと stui に送り、切れた連携は外す") { xa =>
    for
      e <- env(xa)
      _ <- drain(e)
      owner <- newUser(e.users, "持ち主"); editor <- newUser(e.users, "編集者")
      _ <- e.tokens.saveToken(owner.id, "stui", e.box.encrypt("owner-token"))
      _ <- e.tokens.saveToken(editor.id, "stui", e.box.encrypt("revoked"))
      f0 <- e.forms.create(owner.id, "問い合わせ", "", List(QuestionDto("name", QuestionKind.Short, "お名前")))
      f <- e.forms.update(f0.id, None, None, Some(FormStatus.Open), Some(FormAccess.Anyone), None, None, None, Some(FormNotifyDto(inApp = false, mail = true, stui = true)),
             Some(List(editor.id))).map(_.get)
      r <- e.forms.submit(f.id, None, Map("name" -> "田中".asJson)).map(_.toOption.get)
      _ <- e.notifier.responseArrived(f, r, None)
      _ <- e.forms.notifications(owner.id, unreadOnly = false, 10).map(l => assertEquals(l.items, Nil)) // inApp を切っている
      fiber <- e.notifier.outboxLoop.start
      _ <- (IO.sleep(scala.concurrent.duration.DurationInt(500).millis) *> (e.mails.get, e.stuiCalls.get, e.tokens.token(editor.id, "stui")).tupled)
             .iterateUntil((m, s, t) => m.size >= 2 && s.nonEmpty && t.isEmpty).timeout(scala.concurrent.duration.DurationInt(40).seconds)
      _ <- fiber.cancel
      mails <- e.mails.get
      _ = assertEquals(mails.map(_.to).toSet, Set(owner.email.get, editor.email.get))
      _ = assert(mails.head.subject == "[smohua] 「問い合わせ」に回答が届きました" && mails.head.body.contains(s"https://smohua.example.com/forms/${f.id}/responses"))
      calls <- e.stuiCalls.get
      _ = assertEquals(calls.map(_._1), List("owner-token"))
      _ = assertEquals(calls.head._2.hcursor.get[String]("url"), Right(s"https://smohua.example.com/forms/${f.id}/responses"))
      // チャンネルに出す設定なら、持ち主の連携で1回だけ
      _ <- e.forms.update(f.id, None, None, None, None, None, None, None, Some(FormNotifyDto(inApp = false, stui = true, stuiChannel = "受付")), None)
      g <- e.forms.find(f.id).map(_.get)
      _ <- e.notifier.responseArrived(g, r, None)
      n <- Ref.of[IO, List[Json]](Nil)
      _ <- e.forms.processOutbox(100)(row => n.update(_ :+ row.payload).as(OutboxResult.Sent))
      _ <- n.get.map(ps => assertEquals(ps.map(_.hcursor.get[String]("channel")), List(Right("受付"))))
    yield ()
  }

  withDb("編集できる人: 回答・lookup は見られるが、編集できる人と stui のチャンネルは変えられない。CSV は古い順") { xa =>
    for
      e <- env(xa)
      owner <- newUser(e.users, "持ち主"); editor <- newUser(e.users, "編集者"); other <- newUser(e.users, "他人")
      ko <- key(e, owner); ke <- key(e, editor); kx <- key(e, other)
      c <- call(e, Method.POST, "/api/forms", Some(ko), Some(Json.obj("title" -> "名簿".asJson, "questions" -> questions)))
      fid = id(c._2)
      _ <- call(e, Method.PATCH, s"/api/forms/$fid", Some(ko), Some(Json.obj("status" -> "open".asJson, "access" -> "anyone".asJson,
             "editorIds" -> List(editor.id).asJson, "notice" -> FormNotifyDto(stui = true, stuiChannel = "総務").asJson))).map(r => assertEquals(r._1, Status.Ok))
      // 編集できる人: 同じチャンネルのままなら、ほかの設定は変えられる
      same <- call(e, Method.PATCH, s"/api/forms/$fid", Some(ke), Some(Json.obj("notice" -> FormNotifyDto(mail = true, stui = true, stuiChannel = " 総務 ").asJson)))
      _ = assertEquals(same._1, Status.Ok, same._2.noSpaces)
      ch <- call(e, Method.PATCH, s"/api/forms/$fid", Some(ke), Some(Json.obj("notice" -> FormNotifyDto(stui = true, stuiChannel = "雑談").asJson)))
      _ = assertEquals(ch._1, Status.BadRequest)
      _ <- call(e, Method.PATCH, s"/api/forms/$fid", Some(ke), Some(Json.obj("editorIds" -> List(other.id).asJson))).map(r => assertEquals(r._1, Status.BadRequest))
      _ <- e.forms.find(UUID.fromString(fid)).map(f => assertEquals(f.map(_.notice.stuiChannel), Some("総務")))
      _ <- call(e, Method.PATCH, s"/api/forms/$fid", Some(ko), Some(Json.obj("notice" -> FormNotifyDto(stui = true, stuiChannel = "雑談").asJson))).map(r => assertEquals(r._1, Status.Ok))
      // lookup: 編集できる人には見え、他人には見えない（並びは頼んだ順）
      c2 <- call(e, Method.POST, "/api/forms", Some(ke), Some(Json.obj("title" -> "編集者のフォーム".asJson)))
      ids = List(id(c2._2), fid, UUID.randomUUID().toString)
      _ <- call(e, Method.POST, "/api/forms/lookup", Some(ke), Some(Json.obj("ids" -> ids.asJson)))
             .map(r => assertEquals(r._2.hcursor.downField("forms").as[List[Json]].map(_.map(id)), Right(ids.take(2))))
      _ <- call(e, Method.POST, "/api/forms/lookup", Some(kx), Some(Json.obj("ids" -> ids.asJson)))
             .map(r => assertEquals(r._2.hcursor.downField("forms").as[List[Json]], Right(Nil)))
      // CSV は古い順（式になる値は無害化する）
      _ <- List("一番目", "=二番目").traverse_(n => call(e, Method.POST, s"/api/public/forms/$fid/responses", None, Some(Json.obj("answers" -> Json.obj("name" -> n.asJson)))))
      csv <- e.app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/forms/$fid/responses.csv")).putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, ke))))
               .flatMap(_.body.compile.to(Array)).map(new String(_, "UTF-8"))
      _ = assert(csv.indexOf("一番目") > 0 && csv.indexOf("一番目") < csv.indexOf("'=二番目"), csv)
      _ <- drain(e)
    yield ()
  }

  withDb("MCP: ツールの一覧・フォームの作成から回答の取得まで") { xa =>
    def rpc(e: Env, k: String, method: String, params: Json = Json.obj()): IO[Json] =
      call(e, Method.POST, "/mcp", Some(k), Some(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 1.asJson, "method" -> method.asJson, "params" -> params))).map(_._2)
    def tool(e: Env, k: String, name: String, args: Json): IO[(Boolean, String)] =
      rpc(e, k, "tools/call", Json.obj("name" -> name.asJson, "arguments" -> args)).map { j =>
        val c = j.hcursor.downField("result")
        (c.get[Boolean]("isError").getOrElse(true), c.downField("content").downArray.get[String]("text").getOrElse(j.noSpaces))
      }
    for
      e <- env(xa)
      u <- newUser(e.users, "山田"); k <- key(e, u); kr <- key(e, u, List("read"))
      _ <- call(e, Method.POST, "/mcp", None, Some(Json.obj())).map(r => assertEquals(r._1, Status.Unauthorized))
      init <- rpc(e, k, "initialize", Json.obj("protocolVersion" -> "2025-06-18".asJson))
      _ = assertEquals(init.hcursor.downField("result").downField("serverInfo").get[String]("name"), Right("smohua"))
      tl <- rpc(e, k, "tools/list")
      names = tl.hcursor.downField("result").downField("tools").as[List[Json]].getOrElse(Nil).flatMap(_.hcursor.get[String]("name").toOption)
      _ = assert(List("list_forms", "get_form", "list_responses", "get_summary", "create_form", "set_form_status", "submit_response", "call_api").forall(names.contains), names)
      title = s"MCP の試験 ${UUID.randomUUID().toString.take(8)}"
      ro <- tool(e, kr, "create_form", Json.obj("title" -> title.asJson))
      _ = assert(ro._1 && ro._2.contains("参照専用"))
      // id を省いた質問には q1, q2… が付く
      c <- tool(e, k, "create_form", Json.obj("title" -> title.asJson, "questions" -> Json.arr(
             Json.obj("kind" -> "single".asJson, "label" -> "色".asJson, "options" -> List("赤", "青").asJson), Json.obj("kind" -> "long".asJson, "label" -> "理由".asJson))))
      _ = assert(!c._1, c._2)
      _ <- tool(e, k, "set_form_status", Json.obj("form" -> title.asJson, "status" -> "open".asJson)).map(r => assert(!r._1 && r._2.contains("/f/"), r._2))
      _ <- tool(e, k, "submit_response", Json.obj("form" -> title.asJson, "answers" -> Json.obj("q1" -> "青".asJson, "q2" -> "空の色".asJson))).map(r => assert(!r._1, r._2))
      _ <- tool(e, k, "submit_response", Json.obj("form" -> title.asJson, "answers" -> Json.obj("q1" -> "緑".asJson))).map(r => assert(r._1 && r._2.contains("選択肢"), r._2))
      l <- tool(e, kr, "list_forms", Json.obj("query" -> title.asJson))
      _ = assert(l._2.contains(s"- $title（受付中）回答 1 件・質問 2 個"), l._2)
      rs <- tool(e, kr, "list_responses", Json.obj("form" -> title.asJson))
      _ = assert(rs._2.contains("全 1 件") && rs._2.contains("  色: 青") && rs._2.contains("  理由: 空の色"), rs._2)
      sm <- tool(e, kr, "get_summary", Json.obj("form" -> title.asJson))
      _ = assert(sm._2.contains("- 色（1つ選ぶ・回答 1）: 赤: 0、青: 1"), sm._2)
      _ <- tool(e, kr, "get_form", Json.obj("form" -> "どこにもない題名のフォーム".asJson)).map(r => assert(r._1))
    yield ()
  }

  withDb("APIキーの発行: 名前は100文字まで、有効期限は3650日まで（大きすぎる日数で落ちない）") { xa =>
    for
      e  <- env(xa)
      u  <- newUser(e.users, "鍵の人")
      ck <- new SessionAuth(e.users, cfg).issue(u)
      post = (body: Json) => e.app.run(Request[IO](Method.POST, uri"/api/me/api-keys").addCookie(cfg.cookieName, ck.content).withEntity(body)).map(_.status)
      long <- post(Json.obj("name" -> ("あ" * 101).asJson, "scopes" -> List("read").asJson))
      huge <- post(Json.obj("name" -> "長すぎる期限".asJson, "scopes" -> List("read").asJson, "expiresInDays" -> Int.MaxValue.asJson))
      good <- post(Json.obj("name" -> "ふつう".asJson, "scopes" -> List("read").asJson, "expiresInDays" -> 365.asJson))
    yield assertEquals(List(long, huge, good), List(Status.BadRequest, Status.BadRequest, Status.Created))
  }

  withDb("プッシュの購読は1人あたり新しい20件まで") { xa =>
    val store = new smohua.push.PushStore(xa)
    for
      e <- env(xa)
      u <- newUser(e.users, "端末の多い人")
      _ <- (1 to 23).toList.traverse_(i => store.subscribe(u.id, smohua.push.Subscription(s"https://fcm.googleapis.com/fcm/send/${UUID.randomUUID()}-$i", "p", "a"), None))
      n <- store.count(u.id)
    yield assertEquals(n, smohua.push.PushStore.MaxPerUser)
  }

  withDb("認証: キーとセッションでグループも読める・失効やログアウトの後は alive が false・call_api で SSE は呼べない") { xa =>
    for
      e  <- env(xa)
      u  <- newUser(e.users, "グループの人")
      g   = s"g-${UUID.randomUUID().toString.take(8)}"
      _  <- e.users.syncGroups(u.id, List(g, "a-" + g))
      sess = new SessionAuth(e.users, cfg)
      k  <- key(e, u)
      kr  = Request[IO](Method.GET, uri"/api/me").putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, k)))
      byKey <- sess.fromRequest(kr)
      _   = assertEquals(byKey.map(_.groups.map(_.name)), Some(List("a-" + g, g)))
      ck <- sess.issue(u)
      cr  = Request[IO](Method.GET, uri"/api/me").addCookie(cfg.cookieName, ck.content)
      bySession <- sess.fromRequest(cr)
      _   = assertEquals(bySession.map(s => (s.user.id, s.groups.size)), Some((u.id, 2)))
      _  <- (sess.alive(kr), sess.alive(cr)).tupled.map(r => assertEquals(r, (true, true)))
      // call_api から終わらない SSE は呼べない
      sse <- call(e, Method.POST, "/mcp", Some(k), Some(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 1.asJson, "method" -> "tools/call".asJson,
               "params" -> Json.obj("name" -> "call_api".asJson, "arguments" -> Json.obj("method" -> "GET".asJson, "path" -> "/api/stream".asJson)))))
      _   = assertEquals(sse._2.hcursor.downField("result").get[Boolean]("isError"), Right(true))
      kid <- e.users.keys(u.id).map(_.head.id)
      _  <- e.users.revokeKey(u.id, kid)
      _  <- sess.revoke(cr)
      _  <- (sess.alive(kr), sess.alive(cr)).tupled.map(r => assertEquals(r, (false, false)))
    yield ()
  }

  withDb("通知の既読: ids が読めないときは 400 で、すべて既読にはしない") { xa =>
    for
      e <- env(xa)
      u <- newUser(e.users, "通知の人")
      k <- key(e, u)
      _ <- e.forms.notify(u.id, "response", None, "知らせ", "", "/")
      bad <- call(e, Method.POST, "/api/notifications/read", Some(k), Some(Json.obj("ids" -> List("not-a-uuid").asJson)))
      n <- e.forms.unread(u.id)
      _ <- call(e, Method.POST, "/api/notifications/read", Some(k), Some(Json.obj("ids" -> Json.arr())))
      m <- e.forms.unread(u.id)
    yield assertEquals((bad._1, n, m), (Status.BadRequest, 1, 0))
  }
