package smohua.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import sbirds.auth.Crypto
import sbirds.oauth.{OAuthCode, OAuthKeyStore, OAuthProvider}
import sbirds.oauth.OAuthProvider.escape
import smohua.auth.ApiKeys
import smohua.core.AuthSession
import smohua.db.*

import java.util.UUID

/**
 * 兄弟のサービス（skiwi など）への認可（OAuth 2.0 の認可コード + PKCE。流れは sbirds.oauth.OAuthProvider）。
 * 発行するトークンは、そのユーザーの API キー（「<クライアント名> 連携」。スコープはクライアントに許したもののうち求められたもの。
 * API キーの画面から失効できる）。連携サービスはこのトークンで、本人が見られるフォームの回答・集計を読む（POST /api/forms/lookup など）。
 */
final class OAuthRoutes(oauth: OAuthKeyStore, activity: ActivityStore, sessions: SessionAuth, cfg: ApiConfig) {

  private val host = new OAuthProvider.Host[AuthSession] {
    val settings = OAuthProvider.Settings("smohua", cfg.cookieSecure, accent = "#0f766e")
    val codes = oauth

    def client(id: String): IO[Option[OAuthClient]] = IO.pure(cfg.oauthClients.find(_.id == id))

    // API キーでは許可できない（同意の画面を経ずにスコープを広げられるため）
    def browserSession(req: Request[IO]): IO[Option[AuthSession]] = sessions.fromRequest(req).map(_.filter(_.isSession))

    def userId(s: AuthSession): UUID = s.user.id

    def consent(req: Request[IO], c: OAuthClient, s: AuthSession, scopes: List[String]): IO[OAuthProvider.Consent] = {
      val name = escape(c.name)
      val items =
        if scopes.contains("write") then "<li>あなたのフォームの作成・変更・回答の削除を、あなたの指定で行うことがあります</li>"
        else "<li>読み取りだけです（投稿・変更・削除はしません）</li>"
      IO.pure(OAuthProvider.Consent(
        s"$name との連携",
        s"""<h1>$name との連携</h1>
           |<p><b>$name</b> が、あなた（${escape(s.user.name)}）の権限で smohua を使います。</p>
           |<ul class="muted">$items<li>あなたが見られるフォームと、その回答・集計だけが対象です</li>
           |<li>個人設定の「APIキー」から、いつでも連携をやめられます</li></ul>""".stripMargin
      ))
    }

    override def authorized(req: Request[IO], s: AuthSession, c: OAuthClient, scopes: List[String]): IO[Unit] =
      activity.audit(Some(s.user.id), "web", "oauth.authorize", Some(c.id), Json.obj())

    def issue(req: Request[IO], c: OAuthClient, oc: OAuthCode): IO[OAuthProvider.Token] = {
      val (key, prefix) = ApiKeys.generate()
      oauth.replaceKey(
        oc.userId,
        c.id,
        s"${c.name} 連携",
        prefix,
        Crypto.sha256(key),
        oc.scope.split(' ').toList.filter(_.nonEmpty)
      ) *>
        activity.audit(Some(oc.userId), "oauth", "api_key.create", Some(c.id), Json.obj("client" -> c.id.asJson))
          .as(OAuthProvider.Token(key, oc.scope))
    }

    def revoke(req: Request[IO], c: OAuthClient, token: String): IO[Unit] =
      oauth.revokeKey(Crypto.sha256(token), c.id)
  }

  val routes: HttpRoutes[IO] = new OAuthProvider(host).routes
}
