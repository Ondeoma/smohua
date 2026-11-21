package smohua.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.links.{LinkFailure, StuiClient}
import smohua.core.ErrorCode
import smohua.db.*
import smohua.shared.*

/**
 * stui との連携（兄弟の skea・skereru と同じやり方. stui の docs/integrations.md. 手続きは sbirds.links.LinkClient）.
 * 各ユーザーが OAuth 2.0（認可コード + PKCE）で stui を許可し、smohua は本人のトークンで通知を送る（POST /api/apps/notify）.
 * トークンは APP_SECRET の鍵で暗号化して保存する. state も DB に置くので、どのノードに戻ってきてもよい.
 */
final class StuiRoutes(stui: Option[StuiClient], activity: ActivityStore, val sessions: SessionAuth, cfg: ApiConfig)
  extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    /** 設定されたサービスと、自分が連携済みかどうか. */
    case req @ GET -> Root / "api" / "integrations" / "links" => getIntegrationsLinks(req)
    case req @ GET -> Root / "api" / "integrations" / "stui" / "channels" => getIntegrationsStuiChannels(req)
    case req @ GET -> Root / "api" / "integrations" / "stui" / "connect" => getIntegrationsStuiConnect(req)
    case req @ GET -> Root / "api" / "integrations" / "stui" / "callback" => getIntegrationsStuiCallback(req)
    case req @ DELETE -> Root / "api" / "integrations" / "stui" / "connection" => deleteIntegrationsStuiConnection(req)
  }

  private def getIntegrationsLinks(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      sessions.appNames(req).flatMap(names =>
        stui.toList.traverse(c =>
          // 名前は sruru に登録した表示名（なければサービスの名前）
          c.link.connected(s.user.id).map(ok => LinkServiceDto(P, names.getOrElse(P, P), c.link.cfg.url, ok))
        )
      ).flatMap(Ok(_))
    )

  /** 投稿先に選べるチャンネル（本人が参加しているもの. 連携していなければ空）. */
  private def getIntegrationsStuiChannels(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService(c =>
        c.channels(s.user.id).flatMap {
          case Right(cs) => Ok(cs.map(x => Json.obj("id" -> Json.fromString(x.id), "name" -> Json.fromString(x.name))))
          case Left(LinkFailure.NotConnected | LinkFailure.Expired) => Ok(Json.arr())
          case Left(_) => err(ErrorCode.NotifyTestFailed, "stui からチャンネルの一覧を読めませんでした")
        }
      )
    )

  private def getIntegrationsStuiConnect(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService(c =>
        c.link.authorizeUrl(s.user.id, callback, param(req, "returnTo").getOrElse("/settings")).flatMap(u =>
          redirect(u)
        )
      )
    )

  private def getIntegrationsStuiCallback(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService { c =>
        param(req, "state").fold(IO.pure(None))(c.link.takeState).flatMap {
          case Some((uid, _, back)) if uid != s.user.id => done(back, "error")
          case None => done("/settings", "error")
          case Some((_, _, back)) if param(req, "error").isDefined =>
            done(back, if param(req, "error").contains("access_denied") then "denied" else "error")
          case Some((_, verifier, back)) =>
            param(req, "code") match {
              case None => done(back, "error")
              case Some(code) =>
                c.link.exchange(s.user.id, code, verifier, callback).flatMap {
                  case true =>
                    activity.audit(Some(s.user.id), "web", "integration.connect", Some(P), Json.obj()) *>
                      done(back, "connected")
                  case false => done(back, "error")
                }
            }
        }
      }
    )

  private def deleteIntegrationsStuiConnection(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService(c =>
        c.link.disconnect(s.user.id) *>
          activity.audit(Some(s.user.id), "web", "integration.disconnect", Some(P), Json.obj()) *> NoContent()
      )
    )

  private val P = "stui"

  private def callback: String = s"${cfg.publicUrl}/api/integrations/$P/callback"

  /** 個人設定へ戻る（結果を integration=…&result=… に付ける）. */
  private def done(returnTo: String, result: String): IO[Response[IO]] = {
    // 同じサイトのパスだけ. 開発中（DEV_LOGIN）は画面の開発サーバー（http://localhost:…）にも戻せる
    val back = Option(returnTo).filter(p => localPath(p) || cfg.devOrigin(p)).getOrElse("/settings")
    redirect(back + (if back.contains("?") then "&" else "?") + s"integration=$P&result=$result")
  }

  private def withService(f: StuiClient => IO[Response[IO]]): IO[Response[IO]] =
    stui.fold(err(ErrorCode.StuiNotConfigured))(f)
}
