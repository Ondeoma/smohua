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
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.staticcontent.*
import smohua.api.*
import smohua.auth.{OidcClient, OidcConfig, SecretBox}
import smohua.db.*
import smohua.notify.{SmtpConfig, SmtpMailer}
import smohua.observe.{HttpClientTelemetry, HttpTelemetry, Metrics, RumRoutes, Telemetry}

import scala.concurrent.duration.*

final case class AppConfig(
    port: Int,
    db: DbConfig,
    oidc: Option[OidcConfig],
    api: ApiConfig,
    staticDir: Option[String],
    smtp: Option[SmtpConfig],
    appSecret: String,
    nodeId: String
)

/** 設定は環境変数から読む。 */
object Config:
  private def opt(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
  private def int(name: String): Option[Int]    = opt(name).flatMap(_.toIntOption)
  private def enabled(name: String): Boolean    = opt(name).exists(_.equalsIgnoreCase("true"))
  private def required(name: String): Either[String, String] = opt(name).toRight(s"環境変数 $name を設定してください")
  private def list(name: String): List[String]  = opt(name).toList.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty))

  /** OAUTH_CLIENTS: [{"id":"skiwi","secret":"…","redirectUri":"https://…/api/integrations/smohua/callback","name":"skiwi","scopes":["read"]}] */
  private def oauthClients: Either[String, List[smohua.api.OAuthClient]] =
    opt("OAUTH_CLIENTS").fold(Right(Nil)) { s =>
      io.circe.parser.parse(s).flatMap(_.as[List[io.circe.Json]]).left.map(_ => "OAUTH_CLIENTS は JSON の配列で指定してください").flatMap(_.traverse { j =>
        val c = j.hcursor
        (c.get[String]("id"), c.get[String]("secret"), c.get[String]("redirectUri")).tupled.toOption
          .filter((id, sec, uri) => id.nonEmpty && sec.length >= 16 && (uri.startsWith("https://") || "^http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/".r.findFirstIn(uri).isDefined))
          .map((id, sec, uri) => smohua.api.OAuthClient(id, sec, uri, c.get[String]("name").getOrElse(id),
            c.get[List[String]]("scopes").toOption.map(_.filter(Set("read", "write"))).filter(_.nonEmpty).getOrElse(List("read"))))
          .toRight("OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://。開発中は http://localhost:…/ も可）を指定してください")
      })
    }

  def load: IO[AppConfig] = IO.fromEither(
    (for
      dbUrl  <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      pub    <- required("PUBLIC_URL")
      devLogin = enabled("DEV_LOGIN")
      _ <- Either.cond(!(devLogin && pub.startsWith("https://")), (), "DEV_LOGIN（開発用のログイン）は https の PUBLIC_URL では使えません")
      oidc <- (opt("OIDC_ISSUER"), opt("OIDC_CLIENT_ID"), opt("OIDC_CLIENT_SECRET")) match
                case (Some(_), Some(_), Some(_)) if devLogin => Left("DEV_LOGIN（開発用のログイン）は OIDC と同時には使えません。本番では DEV_LOGIN を外してください")
                case (Some(i), Some(c), Some(s)) =>
                  Right(Some(OidcConfig(i, c, s, s"${pub.stripSuffix("/")}/auth/callback", opt("OIDC_GROUPS_CLAIM").getOrElse("groups"))))
                case (None, None, None) if devLogin => Right(None)
                case _ => Left("OIDC_ISSUER・OIDC_CLIENT_ID・OIDC_CLIENT_SECRET を設定してください（開発中は DEV_LOGIN=true で省略できます）")
      secret <- opt("APP_SECRET") match
                  case Some(s) if s.length >= 16 => Right(s)
                  case Some(_) => Left("APP_SECRET は16文字以上にしてください")
                  case None if devLogin => Right("smohua-development-secret")
                  case None => Left("APP_SECRET（16文字以上の秘密の値）を設定してください")
      clients <- oauthClients
      proxies <- list("TRUSTED_PROXIES").traverse(s => RateLimitConfig.proxy(s).toRight(s"TRUSTED_PROXIES の「$s」は IP か CIDR で書いてください"))
      tz <- opt("TIMEZONE").getOrElse("Asia/Tokyo") match
              case z if scala.util.Try(java.time.ZoneId.of(z)).isSuccess => Right(z)
              case z => Left(s"TIMEZONE が正しくありません: $z（例: Asia/Tokyo）")
    yield
      val base = pub.stripSuffix("/")
      AppConfig(
        port = int("HTTP_PORT").getOrElse(8080),
        db = DbConfig(dbUrl, dbUser, dbPass, int("DATABASE_POOL_SIZE").getOrElse(10)),
        oidc = oidc,
        api = ApiConfig(
          adminGroups = list("ADMIN_GROUPS").toSet,
          cookieSecure = base.startsWith("https://"),
          publicUrl = base,
          devLogin = devLogin,
          timezone = tz,
          oauthClients = clients,
          // STUI_URL・STUI_CLIENT_ID・STUI_CLIENT_SECRET（STUI_INTERNAL_URL はサーバーから呼ぶ URL）。stui に smohua をクライアントとして登録しておく
          stui = (opt("STUI_URL"), opt("STUI_CLIENT_ID"), opt("STUI_CLIENT_SECRET")).mapN((u, id, sec) =>
            smohua.api.LinkService("stui", u.stripSuffix("/"), id, sec, opt("STUI_INTERNAL_URL").map(_.stripSuffix("/")))),
          mail = opt("SMTP_HOST").isDefined,
          // ログインしなくても使える入口の、IP ごとの1分あたりの回数（0 なら制限しない）
          rateLimit = RateLimitConfig(int("RATE_LIMIT_AUTH").getOrElse(20), int("RATE_LIMIT_PUBLIC").getOrElse(120),
            int("RATE_LIMIT_SUBMIT").getOrElse(20), proxies)
        ),
        staticDir = opt("STATIC_DIR"),
        // メールの通知（SMTP）。送り先を skereru にすれば skereru に届く
        smtp = opt("SMTP_HOST").map { h =>
          val tls = opt("SMTP_TLS").getOrElse("starttls").toLowerCase
          SmtpConfig(h, int("SMTP_PORT").getOrElse(if tls == "ssl" then 465 else if tls == "none" then 25 else 587), opt("SMTP_USER"),
            opt("SMTP_PASSWORD"), tls, opt("MAIL_FROM").getOrElse(s"smohua@$h"))
        },
        appSecret = secret,
        nodeId = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName + "-" + java.util.UUID.randomUUID().toString.take(8))
      )
    ).left.map(new IllegalArgumentException(_))
  )

object Main extends IOApp.Simple:

  // ログにトレースの ID・操作したユーザーを載せるため、IOLocal の文脈を Java のライブラリから見えるようにする
  // （IORuntime を作る前に設定する必要がある. オブジェクトの初期化は main より先に動く）
  Telemetry.prepare()

  private val version = Option(getClass.getPackage.getImplementationVersion).orElse(sys.env.get("APP_VERSION")).getOrElse("dev")

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.slf4j.Slf4jFactory.create[IO]

  def run: IO[Unit] =
    for
      cfg <- Config.load
      n   <- migrateWithRetry(cfg)
      _   <- smohua.observe.Log.println(s"[smohua] database migrated ($n migration(s) applied); node ${cfg.nodeId}")
      _ <- (for
        _     <- Telemetry.resource("smohua", version, runtime)
        xa    <- Database.transactor(cfg.db)
        _     <- gauges(xa)
        // 外への呼び出しもトレースに出す（traceparent を付けて送る）
        http  <- EmberClientBuilder.default[IO].withTimeout(30.seconds).build.map(HttpClientTelemetry(_))
        users    = new UserStore(xa)
        forms    = new FormStore(xa)
        activity = new ActivityStore(xa)
        tokens   = new IntegrationTokenStore(xa)
        lock     = new JobLock(xa, cfg.nodeId)
        box      = new SecretBox(cfg.appSecret)
        oidc  <- Resource.eval(cfg.oidc.traverse(OidcClient.create(_, http)))
        sess     = new SessionAuth(users, cfg.api)
        pushStore = new smohua.push.PushStore(xa)
        push    <- Resource.eval(smohua.push.PushService.create(pushStore, http, cfg.api.publicUrl, "smohua"))
        notifier = new NotifyService(forms, users, tokens, box, http, cfg.smtp.map(new SmtpMailer(_)), Some(push), cfg.api)
        svc      = new FormService(forms, notifier, activity, cfg.api)
        feed    <- Resource.eval(EventFeed(forms))
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
        all <- Resource.eval(cfg.staticDir.traverse(spa)).map(_.fold(app)(app <+> _))
        httpApp <- HttpTelemetry(ErrorHandling.Custom.recoverWith(Guards(RateLimit(all, cfg.api.rateLimit), cfg.api).orNotFound)(Guards.recover), List("/api", "/auth", "/mcp", "/oauth"),
                     RateLimit.clientIp(_, cfg.api.rateLimit.trustedProxies))
        _ <- EmberServerBuilder.default[IO]
               .withHost(ipv4"0.0.0.0")
               .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
               .withShutdownTimeout(5.seconds)
               .withHttpApp(httpApp)
               .build
        _ <- Resource.eval(smohua.observe.Log.println(s"[smohua] listening on :${cfg.port} (${cfg.api.publicUrl})"))
      yield ()).useForever
    yield ()

  /** DB（Patroni のプライマリ）が使えるまで待ってからマイグレーションする。複数ノード同時起動でも Flyway のロックで1回だけ適用される。 */
  private def migrateWithRetry(cfg: AppConfig, attempt: Int = 1): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else smohua.observe.Log.println(s"[smohua] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *> IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  /** DB から定期的に読むメトリクス（skorimako のダッシュボード・アラート用）。 */
  private def gauges(xa: doobie.Transactor[IO]): Resource[IO, Unit] =
    def q(sqlText: doobie.Fragment): IO[Long] = sqlText.query[Long].unique.transact(xa)
    List(
      Metrics.gauge("smohua.users.total", "有効なユーザーの数")(q(sql"select count(*) from app_user where not disabled")),
      Metrics.gauge("smohua.users.active_24h", "24時間以内にログインしたユーザーの数")(q(sql"select count(*) from app_user where last_login_at > now() - interval '24 hours'")),
      Metrics.gauge("smohua.sessions.active", "有効なセッションの数")(q(sql"select count(*) from session where expires_at > now()")),
      Metrics.gauge("smohua.forms.total", "フォームの数")(q(sql"select count(*) from form where deleted_at is null")),
      Metrics.gauge("smohua.forms.open", "受付中のフォームの数")(q(sql"select count(*) from form where deleted_at is null and status = 'open'")),
      Metrics.gauge("smohua.responses.total", "回答の数")(q(sql"select count(*) from form_response")),
      Metrics.gauge("smohua.responses.last_24h", "24時間以内に届いた回答の数")(q(sql"select count(*) from form_response where created_at > now() - interval '24 hours'")),
      Metrics.gauge("smohua.notify.outbox", "送信待ちの通知（メール・stui）の数")(q(sql"select count(*) from notify_outbox where sent_at is null and not skipped"))
    ).sequence_

  /** ロードバランサー用のヘルスチェック。DB（プライマリ）に書けない状態なら 503。 */
  private def healthRoutes(xa: doobie.Transactor[IO], node: String): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true)  => text(Status.Ok, s"ok $node")
        case Right(false) => text(Status.ServiceUnavailable, "database is read-only (not primary)")
        // 例外の文（接続先のホストなど）は外に出さず、ログにだけ残す
        case Left(e)      => smohua.observe.Log.warn(s"health check failed: $e") *> text(Status.ServiceUnavailable, "database unavailable")
      }
  }

  private def text(status: Status, body: String): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body)(using EntityEncoder.stringEncoder))

  private def startupNotes(cfg: AppConfig): IO[Unit] =
    IO.whenA(cfg.smtp.isEmpty)(smohua.observe.Log.println("[smohua] SMTP_HOST が未設定のため、メールの通知は送りません")) *>
      IO.whenA(cfg.api.stui.isEmpty)(smohua.observe.Log.println("[smohua] STUI_URL が未設定のため、stui への通知は送りません")) *>
      IO.whenA(cfg.oidc.isEmpty)(smohua.observe.Log.println("[smohua] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください"))

  /** 期限切れのセッション・古い通知・変更履歴・送信済みの通知・監査ログ・削除したフォームを消す（1ノードだけ）。 */
  private def purgeLoop(users: UserStore, forms: FormStore, activity: ActivityStore, lock: JobLock): IO[Unit] =
    Periodic.run(10.minutes, 1.hour)(e => s"[smohua] purge failed: $e")(lock.exclusive("purge", 50.minutes)(
      IO.realTimeInstant.flatMap(users.purgeExpired) *> forms.purgeNotifications(180).void *> forms.purgeEvents(14).void *>
        forms.purgeOutbox(30).void *> forms.purgeDeleted(30).void *> activity.purgeAudit(730).void))

  /** ビルド済みの画面の配信。存在しないパスは index.html（SPA）。CSP は index.html の中身（インラインのスクリプトのハッシュ）から起動時に作る。 */
  private def spa(dir: String): IO[HttpRoutes[IO]] =
    IO.blocking(java.nio.file.Files.readString(java.nio.file.Path.of(dir, "index.html"))).map { html =>
      val csp   = Guards.pageCsp(html, embed = false, RumRoutes.origin)
      val embed = Guards.pageCsp(html, embed = true, RumRoutes.origin)
      val files = fileService[IO](FileService.Config(dir))
      val index = HttpRoutes.of[IO] {
        case req @ GET -> _ if !Set("api", "auth", "mcp", "oauth").exists(p => req.pathInfo.renderString.startsWith("/" + p)) =>
          StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(NotFound())
      }
      HttpRoutes[IO] { req =>
        val cc = if req.pathInfo.renderString.startsWith("/assets/") then "public, max-age=31536000, immutable" else "no-cache"
        (files <+> index).run(req).map(_.putHeaders("Cache-Control" -> cc, "Content-Security-Policy" -> (if Guards.embeddable(req) then embed else csp)))
      }
    }
