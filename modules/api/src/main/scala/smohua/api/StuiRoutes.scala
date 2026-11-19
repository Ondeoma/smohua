package smohua.api

import sbirds.links.IntegrationTokenStore
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import sbirds.auth.Crypto
import smohua.auth.SecretBox
import smohua.core.ErrorCode
import smohua.db.*
import smohua.shared.*

import java.time.Instant
import scala.concurrent.duration.*

/**
 * stui との連携（兄弟の skea・skorimako と同じやり方。stui の docs/integrations.md）。
 * 各ユーザーが OAuth 2.0（認可コード + PKCE）で stui を許可し、smohua は本人のトークンで通知を送る（POST /api/apps/notify）。
 * トークンは APP_SECRET の鍵で暗号化して保存する。state も DB に置くので、どのノードに戻ってきてもよい。
 */
final class StuiRoutes(store: IntegrationTokenStore,
                       http: Client[IO],
                       box: SecretBox,
                       activity: ActivityStore,
                       val sessions: SessionAuth,
                       cfg: ApiConfig)
  extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    /** 設定されたサービスと、自分が連携済みかどうか。 */
    case req @ GET -> Root / "api" / "integrations" / "links" => getIntegrationsLinks(req)
    case req @ GET -> Root / "api" / "integrations" / "stui" / "connect" => getIntegrationsStuiConnect(req)
    case req @ GET -> Root / "api" / "integrations" / "stui" / "callback" => getIntegrationsStuiCallback(req)
    case req @ DELETE -> Root / "api" / "integrations" / "stui" / "connection" => deleteIntegrationsStuiConnection(req)
  }

  private def getIntegrationsLinks(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      sessions.appNames(req).flatMap(names =>
        cfg.stui.toList.traverse(c =>
          // 名前は sruru に登録した表示名（なければサービスの名前）
          store.token(s.user.id, P).map(t => LinkServiceDto(P, names.getOrElse(P, P), c.url, t.isDefined))
        )
      ).flatMap(Ok(_))
    )

  private def getIntegrationsStuiConnect(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService { c =>
        val state = Crypto.randomToken(24)
        val verifier = Crypto.randomToken(48)
        store.saveState(
          state,
          s.user.id,
          P,
          verifier,
          param(req, "returnTo").getOrElse("/settings"),
          Instant.now().plusSeconds(600)
        ) *>
          redirect(Uri.unsafeFromString(s"${c.url}/oauth/authorize").withQueryParams(Map(
            "response_type" -> "code",
            "client_id" -> c.clientId,
            "redirect_uri" -> callback,
            "scope" -> "read write",
            "state" -> state,
            "code_challenge" -> Crypto.pkceChallenge(verifier),
            "code_challenge_method" -> "S256"
          )).renderString)
      }
    )

  private def getIntegrationsStuiCallback(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService { c =>
        param(req, "state").fold(IO.pure(None))(st => store.takeState(st, P)).flatMap {
          case Some((uid, _, back)) if uid != s.user.id => done(back, "error")
          case None => done("/settings", "error")
          case Some((_, _, back)) if param(req, "error").isDefined =>
            done(back, if param(req, "error").contains("access_denied") then "denied" else "error")
          case Some((_, verifier, back)) =>
            param(req, "code") match {
              case None => done(back, "error")
              case Some(code) =>
                val form = UrlForm(
                  "grant_type" -> "authorization_code",
                  "code" -> code,
                  "redirect_uri" -> callback,
                  "code_verifier" -> verifier
                )
                http.run(Request[IO](
                  Method.POST,
                  Uri.unsafeFromString(s"${c.apiUrl}/oauth/token")
                ).withEntity(form).putHeaders(basic(c)))
                  .use(r =>
                    if r.status.isSuccess then r.as[Json].map(_.hcursor.get[String]("access_token").toOption)
                    else IO.pure(None)
                  )
                  .timeout(15.seconds).handleError(_ => None).flatMap {
                    case Some(token) =>
                      store.saveToken(s.user.id, P, box.encrypt(token)) *>
                        activity.audit(Some(s.user.id), "web", "integration.connect", Some(P), Json.obj()) *>
                        done(back, "connected")
                    case None => done(back, "error")
                  }
            }
        }
      }
    )

  private def deleteIntegrationsStuiConnection(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService { c =>
        store.token(s.user.id, P).map(_.flatMap(box.decrypt)).flatMap(_.traverse_ { t =>
          http.run(Request[IO](
            Method.POST,
            Uri.unsafeFromString(s"${c.apiUrl}/oauth/revoke")
          ).withEntity(UrlForm("token" -> t)).putHeaders(basic(c)))
            .use(_ => IO.unit).timeout(10.seconds).handleError(_ => ())
        }) *> store.deleteToken(s.user.id, P) *>
          activity.audit(Some(s.user.id), "web", "integration.disconnect", Some(P), Json.obj()) *> NoContent()
      }
    )

  private val P = "stui"

  private def callback: String = s"${cfg.publicUrl}/api/integrations/$P/callback"

  private def basic(c: LinkService) = Authorization(BasicCredentials(c.clientId, c.clientSecret))

  /** 個人設定へ戻る（結果を integration=…&result=… に付ける）。 */
  private def done(returnTo: String, result: String): IO[Response[IO]] = {
    // 同じサイトのパスだけ。開発中（DEV_LOGIN）は画面の開発サーバー（http://localhost:…）にも戻せる
    val back = Option(returnTo).filter(p => localPath(p) || cfg.devOrigin(p)).getOrElse("/settings")
    redirect(back + (if back.contains("?") then "&" else "?") + s"integration=$P&result=$result")
  }

  private def withService(f: LinkService => IO[Response[IO]]): IO[Response[IO]] =
    cfg.stui.fold(err(ErrorCode.StuiNotConfigured))(f)

}
