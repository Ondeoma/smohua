package smohua.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{`WWW-Authenticate`, Allow, Authorization}
import smohua.core.{AuthSession, ErrorCode}
import sbirds.http.Errors
import smohua.shared.*

import scala.concurrent.duration.*

/**
 * MCP（Model Context Protocol）のサーバー。POST /mcp に JSON-RPC を送る（Streamable HTTP。応答は JSON、SSE は使わない）。
 * 認証は APIキー（Bearer）だけ。各ツールは REST API をそのキーで内部から呼ぶので、権限・スコープ・監査は REST API と同じになる。
 */
final class McpRoutes(api: HttpRoutes[IO], sessions: SessionAuth, publicUrl: String) extends Http4sDsl[IO] {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "mcp" => postMcp(req)
    case (GET | DELETE) -> Root / "mcp" => mcpMethodNotAllowed()
    case GET -> Root / "api" / "endpoints" => getEndpoints()
  }

  private def postMcp(req: Request[IO]): IO[Response[IO]] =
    req.headers.get[Authorization] match {
      case Some(auth @ Authorization(Credentials.Token(AuthScheme.Bearer, _))) =>
        sessions.fromRequest(Request[IO](Method.GET, Uri.unsafeFromString("/mcp")).putHeaders(auth)).flatMap {
          case None => unauthorized
          case Some(session) =>
            val call = Caller(auth)
            req.as[Json].attempt.flatMap {
              case Left(_) => BadRequest(rpcFail(Json.Null, -32700, "parse error"))
              case Right(j) =>
                j.asArray match {
                  case Some(batch) if batch.size > 50 => BadRequest(rpcFail(Json.Null, -32600, "バッチは50件までです"))
                  case Some(batch) => batch.toList.traverse(handle(call, session, _)).map(_.flatten).flatMap(rs =>
                      if rs.isEmpty then Accepted() else Ok(rs.asJson)
                    )
                  case None => handle(call, session, j).flatMap(_.fold(Accepted())(Ok(_)))
                }
            }
        }
      case _ => unauthorized
    }

  private def mcpMethodNotAllowed(): IO[Response[IO]] =
    MethodNotAllowed(Allow(Method.POST))

  private def getEndpoints(): IO[Response[IO]] =
    Ok(ApiCatalog.all.map(e =>
      Json.obj("method" -> e.method.asJson, "path" -> e.path.asJson, "description" -> e.description.asJson)
    ))

  private val base = publicUrl.stripSuffix("/")
  private val Versions = List("2025-06-18", "2025-03-26", "2024-11-05")
  private val CallTimeout = 30.seconds

  /** call_api で呼べない API（画面への知らせの SSE。応答が終わらない）。 */
  private val NotCallable = Set("/api/stream")

  // ------------------------------------------------------------------ REST API の内部呼び出し

  private final class Caller(auth: Authorization) {
    def apply(method: Method, uri: Uri, body: Option[Json] = None): IO[Either[String, Json]] = {
      val req0 = Request[IO](method, uri).putHeaders(auth, "X-Smohua-Mcp" -> "1")
      val req = body.fold(req0)(req0.withEntity(_))
      // 終わらない応答（SSE など）で、ツールの呼び出しが返らなくならないよう時間を区切る
      api.run(req).value.flatMap {
        case None => IO.pure(Left("404 見つかりません"))
        case Some(r) =>
          r.bodyText.compile.string.map { s =>
            val j = if s.isEmpty then Json.Null else parse(s).getOrElse(Json.fromString(s))
            if r.status.isSuccess then Right(j)
            else Left(s"${r.status.code} ${j.hcursor.get[String]("error").getOrElse(r.status.reason)}")
          }
      }.timeoutTo(CallTimeout, IO.pure(Left("時間内に応答がありませんでした")))
    }
  }

  private def path(segments: String*): Uri =
    Uri(path = Uri.Path(segments.toVector.map(Uri.Path.Segment(_)), absolute = true))

  // ------------------------------------------------------------------ ツール

  private type Result = IO[Either[String, String]]

  private final case class Tool(name: String,
                                description: String,
                                schema: Json,
                                readOnly: Boolean,
                                destructive: Boolean = false)(val run: (Caller, ACursor) => Result) {
    def json: Json = Json.obj(
      "name" -> name.asJson,
      "description" -> description.asJson,
      "inputSchema" -> schema,
      "annotations" -> Json.obj(
        "readOnlyHint" -> readOnly.asJson,
        "destructiveHint" -> destructive.asJson,
        "openWorldHint" -> false.asJson
      )
    )
  }

  private def schema(required: List[String], props: (String, Json)*): Json =
    Json.obj("type" -> "object".asJson, "properties" -> Json.obj(props*), "required" -> required.asJson)
  private def str(d: String): Json = Json.obj("type" -> "string".asJson, "description" -> d.asJson)
  private def int(d: String): Json = Json.obj("type" -> "integer".asJson, "description" -> d.asJson)
  private def strs(d: String): Json =
    Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson), "description" -> d.asJson)
  private def obj(d: String): Json = Json.obj("type" -> "object".asJson, "description" -> d.asJson)

  private def need(a: ACursor, k: String): Either[String, String] =
    a.get[String](k).toOption.map(_.trim).filter(_.nonEmpty).toRight(s"$k を指定してください")
  private def opt(a: ACursor, k: String): Option[String] = a.get[String](k).toOption.map(_.trim)
  private def s(j: Json, k: String): String = j.hcursor.get[String](k).getOrElse("")

  extension [A](e: Either[String, A]) {
    private def withArgs(f: A => Result): Result = e.fold(err => IO.pure(Left(err)), f)
  }
  extension (r: IO[Either[String, Json]]) {
    private def asText: Result = r.map(_.map(_.spaces2))
  }

  /** フォームの指定（ID・回答用や編集用の URL・題名）を ID にする。 */
  private def formId(call: Caller, v: String): IO[Either[String, String]] =
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}".r.findFirstIn(v) match {
      case Some(id) => IO.pure(Right(id.toLowerCase))
      case None =>
        call(Method.GET, path("api", "forms").withQueryParam("q", v.trim)).map(_.flatMap { j =>
          val hits = j.asArray.toList.flatten
          hits.find(f => s(f, "title") == v.trim).orElse(Option.when(hits.size == 1)(hits.head)).map(f => s(f, "id"))
            .toRight(if hits.isEmpty then s"フォーム「$v」が見つかりません" else s"「$v」に当たるフォームが ${hits.size} 個あります。ID で指定してください")
        })
    }

  private def withFormId(call: Caller, a: ACursor)(f: String => Result): Result =
    need(a, "form").withArgs(v => formId(call, v).flatMap(_.fold(e => IO.pure(Left(e)), f)))

  private val formArg = "form" -> str("フォーム（ID・URL・題名）")

  /** 回答1件を読みやすい形に（質問文: 回答）。 */
  private def responseText(qs: List[QuestionDto], r: ResponseDto): String = {
    val who = r.respondent.fold("")(u => s" ${u.name}")
    (s"#${r.seq} ${r.createdAt.toString.take(16).replace('T', ' ')}$who（id=${r.id}）" ::
      qs.flatMap(q => Option(Answers.of(r.answers, q)).filter(_.nonEmpty).map(v => s"  ${q.label}: $v"))).mkString("\n")
  }

  private val questionsSchema = Json.obj(
    "type" -> "array".asJson,
    "description" ->
      "質問の配列 [{id（半角英数字。省略すると q1, q2…）, kind: short|long|single|multi|dropdown|scale|date|number, label, help?, required?, options?（single・multi・dropdown）, scaleMin?, scaleMax?}]".asJson,
    "items" -> Json.obj("type" -> "object".asJson)
  )

  /** id のない質問に q1, q2… を付ける。 */
  private def questions(a: ACursor): Option[Json] =
    a.downField("questions").focus.flatMap(_.asArray).map(qs =>
      Json.fromValues(qs.zipWithIndex.map((q, i) =>
        if q.hcursor.get[String]("id").exists(_.nonEmpty) then q
        else q.deepMerge(Json.obj("id" -> s"q${i + 1}".asJson))
      ))
    )

  private val tools: List[Tool] = List(
    Tool("get_me", "自分（この APIキーの持ち主）の情報とキーのスコープ。", schema(Nil), readOnly = true)((call, _) =>
      call(Method.GET, path("api", "me")).asText
    ),
    Tool(
      "list_forms",
      "自分が持ち主か編集できるフォームの一覧（状態・回答の数つき）。query で題名を絞れる。",
      schema(Nil, "query" -> str("題名に含まれる文字")),
      readOnly = true
    ) {
      (call, a) =>
        val u = opt(a, "query").filter(_.nonEmpty).fold(path("api", "forms"))(q =>
          path("api", "forms").withQueryParam("q", q)
        )
        call(Method.GET, u).map(_.map { j =>
          val fs = j.asArray.toList.flatten
          if fs.isEmpty then "（フォームはありません）"
          else
            fs.map(f =>
              s"- ${s(f, "title")}（${FormStatus.label(s(f, "status"))}）回答 ${f.hcursor.get[Int]("responseCount").getOrElse(0)} 件・質問 ${f.hcursor.get[Int]("questionCount").getOrElse(0)} 個 id=${s(f, "id")}"
            ).mkString("\n")
        })
    },
    Tool("get_form", "フォームの中身（質問・公開の状態・設定）。", schema(List("form"), formArg), readOnly = true) {
      (call, a) =>
        withFormId(call, a)(id =>
          call(Method.GET, path("api", "forms", id)).map(_.map(j =>
            j.deepMerge(Json.obj("fillUrl" -> s"$base${Paths.fill(java.util.UUID.fromString(id))}".asJson)).spaces2
          ))
        )
    },
    Tool(
      "list_responses",
      "フォームの回答を読む（新しいものから limit 件）。before に前の結果の最後の番号（#）を渡すと続きを読める。",
      schema(List("form"), formArg, "limit" -> int("件数（既定20、最大200）"), "before" -> int("この番号より前")),
      readOnly = true
    ) {
      (call, a) =>
        withFormId(call, a) { id =>
          val u0 = path("api", "forms", id, "responses").withQueryParam(
            "limit",
            math.min(200, a.get[Int]("limit").getOrElse(20))
          )
          val u = a.get[Long]("before").toOption.fold(u0)(b => u0.withQueryParam("before", b))
          (call(Method.GET, path("api", "forms", id)), call(Method.GET, u)).mapN((f, p) =>
            (f, p).tupled.map {
              (form, page) =>
                val qs = form.hcursor.get[List[QuestionDto]]("questions").getOrElse(Nil)
                val rs = page.hcursor.get[List[ResponseDto]]("responses").getOrElse(Nil)
                s"「${s(form, "title")}」の回答 全 ${page.hcursor.get[Int]("total").getOrElse(0)} 件\n" +
                  (if rs.isEmpty then "（回答はありません）" else rs.map(responseText(qs, _)).mkString("\n"))
            }
          )
        }
    },
    Tool("get_summary", "フォームの回答の集計（選択肢ごとの件数・段階や数の平均・記述の新しいもの）。", schema(List("form"), formArg), readOnly = true) {
      (call, a) =>
        withFormId(call, a)(id =>
          call(
            Method.GET,
            path("api", "forms", id, "summary")
          ).map(_.flatMap(_.as[FormSummaryStatsDto].left.map(_.getMessage)).map { st =>
            (s"「${st.title}」（${FormStatus.label(st.status)}）回答 ${st.total} 件" :: st.questions.map { q =>
              val detail =
                if q.counts.nonEmpty then
                  q.counts.map(c => s"${c.label}: ${c.count}").mkString("、") + q.average.fold("")(v => s"（平均 $v）")
                else q.average.fold("")(v => s"平均 $v ") + q.texts.map(t => s"「${t.take(200)}」").mkString(" ")
              s"- ${q.label}（${QuestionKind.label(q.kind)}・回答 ${q.answered}）: $detail"
            }).mkString("\n")
          })
        )
    },
    Tool(
      "create_form",
      "フォームを作る（下書き。公開するには set_form_status で open にする）。",
      schema(
        List("title"),
        "title" -> str("題名"),
        "description" -> str("説明"),
        "questions" -> questionsSchema
      ),
      readOnly = false
    ) {
      (call, a) =>
        need(a, "title").withArgs(t =>
          call(
            Method.POST,
            path("api", "forms"),
            Some(Json.obj(
              "title" -> t.asJson,
              "description" -> opt(a, "description").asJson,
              "questions" -> questions(a).asJson
            ).dropNullValues)
          ).map(_.map(f => s"フォームを作りました（下書き）: $base${Paths.form(java.util.UUID.fromString(s(f, "id")))}"))
        )
    },
    Tool(
      "update_form",
      "フォームの題名・説明・質問を変える（questions は全体を置き換える。既にある質問の id は変えないこと。変えると前の回答と結び付かなくなる）。",
      schema(List("form"), formArg, "title" -> str("題名"), "description" -> str("説明"), "questions" -> questionsSchema),
      readOnly = false
    ) {
      (call, a) =>
        withFormId(call, a)(id =>
          call(
            Method.PATCH,
            path("api", "forms", id),
            Some(Json.obj(
              "title" -> opt(a, "title").asJson,
              "description" -> opt(a, "description").asJson,
              "questions" -> questions(a).asJson
            ).dropNullValues)
          ).map(_.map(_ => s"変えました: $base${Paths.form(java.util.UUID.fromString(id))}"))
        )
    },
    Tool(
      "set_form_status",
      "フォームを公開する（open）・締め切る（closed）・下書きに戻す（draft）。",
      schema(
        List("form", "status"),
        formArg,
        "status" -> str("draft / open / closed")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "status")).withArgs(st =>
          withFormId(call, a)(id =>
            call(Method.PATCH, path("api", "forms", id), Some(Json.obj("status" -> st.asJson))).map(_.map(f =>
              s"${FormStatus.label(s(f, "status"))}にしました。回答用の URL: $base${Paths.fill(java.util.UUID.fromString(id))}"
            ))
          )
        )
    },
    Tool(
      "submit_response",
      "フォームに回答する（この APIキーの持ち主として）。answers は {質問の id: 値}。複数選ぶ質問は文字列の配列。",
      schema(List("form", "answers"), formArg, "answers" -> obj("質問の id → 値")),
      readOnly = false
    ) {
      (call, a) =>
        withFormId(call, a)(id =>
          call(
            Method.POST,
            path("api", "public", "forms", id, "responses"),
            Some(Json.obj("answers" -> a.downField("answers").focus.filter(_.isObject).getOrElse(Json.obj())))
          ).map(_.map(_ => "回答しました"))
        )
    },
    Tool(
      "list_notifications",
      "自分への通知（回答が届いた知らせ）。",
      schema(Nil, "unreadOnly" -> Json.obj("type" -> "boolean".asJson)),
      readOnly = true
    ) {
      (call, a) =>
        val u = if a.get[Boolean]("unreadOnly").contains(true) then
          path("api", "notifications").withQueryParam("unread", "true")
        else path("api", "notifications")
        call(Method.GET, u).asText
    },
    Tool(
      "list_api_endpoints",
      "REST API の一覧。専用のツールがない操作（回答の削除・通知の設定・編集できる人の変更など）は call_api で行える。",
      schema(Nil),
      readOnly = true
    ) {
      (_, _) =>
        IO.pure(Right(ApiCatalog.all.map(e => s"${e.method} ${e.path} — ${e.description}").mkString("\n")))
    },
    Tool(
      "call_api",
      "任意の REST API を呼ぶ（画面でできることはすべてできる。list_api_endpoints を参照）。権限はこのキーの持ち主と同じ。",
      schema(
        List("method", "path"),
        "method" -> str("GET / POST / PUT / PATCH / DELETE"),
        "path" -> str("/api/ で始まるパス（クエリを含めてよい）"),
        "body" -> obj("JSON の本文")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "method").map(_.toUpperCase), need(a, "path")).tupled.withArgs {
          (m, p) =>
            (
              Method.fromString(m).toOption.filter(x => Set("GET", "POST", "PUT", "PATCH", "DELETE")(x.name)),
              Uri.fromString(p).toOption.filter(u =>
                u.path.renderString.startsWith("/api/") && !NotCallable.contains(u.path.renderString.stripSuffix("/"))
              )
            ) match {
              case (Some(method), Some(uri)) => call(method, uri, a.downField("body").focus.filterNot(_.isNull)).asText
              case (None, _) => IO.pure(Left("method は GET / POST / PUT / PATCH / DELETE です"))
              case _ => IO.pure(Left("path は /api/ で始めてください（/api/stream は使えません）"))
            }
        }
    }
  )

  private val byName = tools.map(t => t.name -> t).toMap

  // ------------------------------------------------------------------ JSON-RPC

  private def ok(id: Json, result: Json): Json = Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "result" -> result)
  private def rpcFail(id: Json, code: Int, msg: String): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "error" -> Json.obj("code" -> code.asJson, "message" -> msg.asJson))
  private def toolResult(r: Either[String, String]): Json =
    Json.obj(
      "content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> r.merge.asJson)),
      "isError" -> r.isLeft.asJson
    )

  private def handle(call: Caller, s: AuthSession, msg: Json): IO[Option[Json]] = {
    val c = msg.hcursor
    val params = c.downField("params")
    c.downField("id").focus match {
      case None => IO.pure(None)
      case Some(id) =>
        c.get[String]("method").getOrElse("") match {
          case "initialize" =>
            val asked = params.get[String]("protocolVersion").toOption
            IO.pure(Some(ok(
              id,
              Json.obj(
                "protocolVersion" -> asked.filter(Versions.contains).getOrElse(Versions.head).asJson,
                "capabilities" -> Json.obj("tools" -> Json.obj("listChanged" -> false.asJson)),
                "serverInfo" -> Json.obj("name" -> "smohua".asJson, "version" -> "0.1.0".asJson),
                "instructions" ->
                  ("smohua（フォーム）のフォームと、集めた回答を読み書きするツールです。" +
                    "list_forms でフォームを、list_responses で回答を、get_summary で集計を読みます。create_form・update_form・set_form_status でフォームを作って公開できます。" +
                    "専用のツールがない操作は list_api_endpoints で REST API を調べ、call_api で行えます。権限はこの APIキーの持ち主と同じです。").asJson
              )
            )))
          case "ping" => IO.pure(Some(ok(id, Json.obj())))
          case "tools/list" => IO.pure(Some(ok(id, Json.obj("tools" -> tools.map(_.json).asJson))))
          case "tools/call" =>
            val name = params.get[String]("name").getOrElse("")
            byName.get(name) match {
              case None => IO.pure(Some(rpcFail(id, -32602, s"unknown tool: $name")))
              case Some(t) if !s.canWrite && !t.readOnly =>
                IO.pure(Some(ok(id, toolResult(Left("参照専用の APIキーでは変更できません")))))
              case Some(t) =>
                val args = params.downField("arguments").focus.filter(_.isObject).getOrElse(Json.obj()).hcursor
                // 例外の文（DB の内容など）は返さず、ログにだけ残す
                t.run(call, args).handleErrorWith(e =>
                  sbirds.observe.Log.report(ErrorCode.McpToolFailed, name, e).as(Left("内部エラーが起きました"))
                )
                  .map(r => Some(ok(id, toolResult(r))))
            }
          case "resources/list" => IO.pure(Some(ok(id, Json.obj("resources" -> Json.arr()))))
          case "prompts/list" => IO.pure(Some(ok(id, Json.obj("prompts" -> Json.arr()))))
          case other => IO.pure(Some(rpcFail(id, -32601, s"method not found: $other")))
        }
    }
  }

  private val unauthorized: IO[Response[IO]] =
    Errors.respond(ErrorCode.McpApiKeyRequired).map(_.putHeaders(`WWW-Authenticate`(Challenge("Bearer", "smohua"))))

}
