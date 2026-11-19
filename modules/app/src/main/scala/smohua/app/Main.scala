package smohua.app

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.implicits.*
import fs2.io.file.Path
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.staticcontent.*
import smohua.api.*
import sbirds.auth.{OidcClient, OidcConfig}
import smohua.auth.SecretBox
import smohua.core.ErrorCode
import sbirds.http.Errors
import smohua.db.*
import sbirds.links.IntegrationTokenStore
import sbirds.mail.{SmtpConfig, SmtpMailer}
import sbirds.observe.{HttpClientTelemetry, HttpTelemetry, Metrics, RumRoutes, Telemetry}

import scala.concurrent.duration.*

final case class AppConfig(
  port: Int,
  db: DbConfig,
  oidc: Option[OidcConfig],
  api: ApiConfig,
  staticDir: Option[String],
  smtp: Option[SmtpConfig],
  appSecret: String,
  nodeId: String)

object Main extends IOApp.Simple {

  // ログにトレースの ID・操作したユーザーを載せるため、IOLocal の文脈を Java のライブラリから見えるようにする
  // （IORuntime を作る前に設定する必要がある. オブジェクトの初期化は main より先に動く）
  Telemetry.prepare()

  private val version =
    Option(getClass.getPackage.getImplementationVersion).orElse(sys.env.get("APP_VERSION")).getOrElse("dev")

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.slf4j.Slf4jFactory.create[IO]

  def run: IO[Unit] =
    for {
      cfg <- Config.load
      n <- migrateWithRetry(cfg)
      _ <- sbirds.observe.Log.println(s"[smohua] database migrated ($n migration(s) applied); node ${cfg.nodeId}")
      _ <- (for {
        _ <- Telemetry.resource("smohua", version, runtime)
        xa <- Database.transactor(cfg.db)
        _ <- gauges(xa)
        // 外への呼び出しもトレースに出す（traceparent を付けて送る）
        http <- EmberClientBuilder.default[IO].withTimeout(30.seconds).build.map(HttpClientTelemetry(_))
        users = new UserStore(xa)
        forms = new FormStore(xa)
        activity = new ActivityStore(xa)
        tokens = new IntegrationTokenStore(xa)
        lock = new JobLock(xa, cfg.nodeId)
        box = SecretBox(cfg.appSecret)
        oidc <- Resource.eval(cfg.oidc.traverse(OidcClient.create(_, http)))
        sess = new SessionAuth(users, cfg.api)
        pushStore = new sbirds.push.PushStore(xa, maxPerUser = Some(PushRoutes.MaxPerUser))
        push <- Resource.eval(sbirds.push.PushService.create(pushStore, http, cfg.api.publicUrl, "smohua"))
        notifier =
          new NotifyService(forms, users, tokens, box, http, cfg.smtp.map(new SmtpMailer(_)), Some(push), cfg.api)
        svc = new FormService(forms, notifier, activity, cfg.api)
        feed <- Resource.eval(EventFeed(forms))
        _ <- Resource.eval(startupNotes(cfg))
        // 通知の送信（全ノード。SKIP LOCKED で取り合う）、画面への知らせ（各ノードが自分につないでいる画面へ）、掃除（リースを取った1ノードだけ）
        loops = List[IO[Unit]](notifier.outboxLoop, feed.loop, purgeLoop(users, forms, activity, lock))
        _ <- loops.traverse_(_.background)
        rest =
          healthRoutes(xa, cfg.nodeId) <+>
            new AuthRoutes(oidc, users, activity, sess, cfg.api).routes <+>
            new UserRoutes(users, activity, sess).routes <+>
            new FormRoutes(forms, svc, sess).routes <+>
            new StreamRoutes(feed, sess).routes <+>
            new OAuthRoutes(new OAuthStore(xa), activity, sess, cfg.api).routes <+>
            new StuiRoutes(tokens, http, box, activity, sess, cfg.api).routes <+>
            RumRoutes.routes <+>
            new PushRoutes(pushStore, push, sess).routes
        app = rest <+> new McpRoutes(rest, sess, cfg.api.publicUrl).routes
        all <- Resource.eval(cfg.staticDir.traverse(spa(_, cfg.oidc.map(_.issuer)))).map(_.fold(app)(app <+> _))
        httpApp <- HttpTelemetry(
          Guards.recovered(Guards(RateLimits(all, cfg.api.rateLimit), cfg.api).orNotFound),
          List("/api", "/auth", "/mcp", "/oauth"),
          sbirds.http.RateLimit.clientIp(_, cfg.api.rateLimit.trustedProxies)
        )
        _ <- EmberServerBuilder.default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
          .withShutdownTimeout(5.seconds)
          .withHttpApp(httpApp)
          .build
        _ <- Resource.eval(sbirds.observe.Log.println(s"[smohua] listening on :${cfg.port} (${cfg.api.publicUrl})"))
      } yield ()).useForever
    } yield ()

  /** DB（Patroni のプライマリ）が使えるまで待ってからマイグレーションする。複数ノード同時起動でも Flyway のロックで1回だけ適用される。 */
  private def migrateWithRetry(
    cfg: AppConfig,
    attempt: Int = 1
  ): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else
        sbirds.observe.Log.println(s"[smohua] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *>
          IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  /** DB から定期的に読むメトリクス（skorimako のダッシュボード・アラート用）。 */
  private def gauges(
    xa: doobie.Transactor[IO]
  ): Resource[IO, Unit] = {
    def q(
      sqlText: doobie.Fragment
    ): IO[Long] = sqlText.query[Long].unique.transact(xa)
    List(
      Metrics.gauge("smohua.users.total", "有効なユーザーの数")(q(sql"select count(*) from app_user where not disabled")),
      Metrics.gauge(
        "smohua.users.active_24h",
        "24時間以内にログインしたユーザーの数"
      )(q(sql"select count(*) from app_user where last_login_at > now() - interval '24 hours'")),
      Metrics.gauge(
        "smohua.sessions.active",
        "有効なセッションの数"
      )(q(sql"select count(*) from session where expires_at > now()")),
      Metrics.gauge("smohua.forms.total", "フォームの数")(q(sql"select count(*) from form where deleted_at is null")),
      Metrics.gauge(
        "smohua.forms.open",
        "受付中のフォームの数"
      )(q(sql"select count(*) from form where deleted_at is null and status = 'open'")),
      Metrics.gauge("smohua.responses.total", "回答の数")(q(sql"select count(*) from form_response")),
      Metrics.gauge(
        "smohua.responses.last_24h",
        "24時間以内に届いた回答の数"
      )(q(sql"select count(*) from form_response where created_at > now() - interval '24 hours'")),
      Metrics.gauge(
        "smohua.notify.outbox",
        "送信待ちの通知（メール・stui）の数"
      )(q(sql"select count(*) from notify_outbox where sent_at is null and not skipped"))
    ).sequence_
  }

  /** ロードバランサー用のヘルスチェック。DB（プライマリ）に書けない状態なら 503。 */
  private def healthRoutes(
    xa: doobie.Transactor[IO],
    node: String
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true) => text(Status.Ok, s"ok $node")
        case Right(false) => text(Status.ServiceUnavailable, "database is read-only (not primary)")
        // 例外の文（接続先のホストなど）は外に出さず、ログにだけ残す
        case Left(e) => sbirds.observe.Log.report(ErrorCode.HealthCheckFailed, e.toString) *>
            text(Status.ServiceUnavailable, "database unavailable")
      }
  }

  private def text(
    status: Status,
    body: String
  ): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body)(using EntityEncoder.stringEncoder))

  private def startupNotes(
    cfg: AppConfig
  ): IO[Unit] =
    IO.whenA(cfg.smtp.isEmpty)(sbirds.observe.Log.println("[smohua] SMTP_HOST が未設定のため、メールの通知は送りません")) *>
      IO.whenA(cfg.api.stui.isEmpty)(sbirds.observe.Log.println("[smohua] STUI_URL が未設定のため、stui への通知は送りません")) *>
      IO.whenA(
        cfg.oidc.isEmpty
      )(sbirds.observe.Log.println("[smohua] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください"))

  /** 期限切れのセッション・古い通知・変更履歴・送信済みの通知・監査ログ・削除したフォームを消す（1ノードだけ）。 */
  private def purgeLoop(
    users: UserStore,
    forms: FormStore,
    activity: ActivityStore,
    lock: JobLock
  ): IO[Unit] =
    Periodic.run(10.minutes, 1.hour, ErrorCode.PurgeFailed)(lock.exclusive("purge", 50.minutes)(
      IO.realTimeInstant.flatMap(users.purgeExpired) *> forms.purgeNotifications(180).void *>
        forms.purgeEvents(14).void *>
        forms.purgeOutbox(30).void *> forms.purgeDeleted(30).void *> activity.purgeAudit(730).void
    ))

  /** ビルド済みの画面の配信。存在しないパスは index.html（SPA）。CSP は index.html の中身（インラインのスクリプトのハッシュ）から起動時に作る。 */
  private def spa(
    dir: String,
    idp: Option[String]
  ): IO[HttpRoutes[IO]] =
    IO.blocking(java.nio.file.Files.readString(java.nio.file.Path.of(dir, "index.html"))).map { html =>
      val csp = Guards.pageCsp(html, embed = false, RumRoutes.origin, idp)
      val embed = Guards.pageCsp(html, embed = true, RumRoutes.origin, idp)
      val files = fileService[IO](FileService.Config(dir))
      val index = HttpRoutes.of[IO] {
        case req @ GET -> _
            if !Set("api", "auth", "mcp", "oauth").exists(p => req.pathInfo.renderString.startsWith("/" + p)) =>
          StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(Errors.respond(ErrorCode.PageNotFound))
      }
      HttpRoutes[IO] { req =>
        val cc =
          if req.pathInfo.renderString.startsWith("/assets/") then "public, max-age=31536000, immutable" else "no-cache"
        (files <+> index).run(req).map(_.putHeaders(
          "Cache-Control" -> cc,
          "Content-Security-Policy" -> (if Guards.embeddable(req) then embed else csp)
        ))
      }
    }
}
