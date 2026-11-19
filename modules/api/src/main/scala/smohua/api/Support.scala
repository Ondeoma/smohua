package smohua.api

import cats.effect.*
import sbirds.shared.AppError

import scala.concurrent.duration.*

final case class ApiConfig(
  adminGroups: Set[String],
  cookieSecure: Boolean,
  publicUrl: String,
  sessionTtl: FiniteDuration = 14.days,
  cookieName: String = "smohua_session",
  devLogin: Boolean = false,
  timezone: String = "Asia/Tokyo",
  version: String = "0.1.0",
  oauthClients: List[OAuthClient] = Nil,
  stui: Option[LinkService] = None,
  mail: Boolean = false,
  rateLimit: RateLimitConfig = RateLimitConfig()) {

  /** 開発中（DEV_LOGIN）だけ、画面の開発サーバー（http://localhost:…・http://127.0.0.1:…）も自分のサイトとして扱う。 */
  def devOrigin(
    url: String
  ): Boolean = devLogin && (url.startsWith("http://localhost:") || url.startsWith("http://127.0.0.1:"))
}

/**
 * 連携する兄弟のサービス（stui: 回答が届いたときの通知）。相手に smohua をクライアントとして登録しておく。
 * url は画面から開く URL、internalUrl はサーバーから呼ぶ URL（省略時は url）。
 */
final case class LinkService(
  provider: String,
  url: String,
  clientId: String,
  clientSecret: String,
  internalUrl: Option[String] = None) {
  def apiUrl: String = internalUrl.getOrElse(url)
}

/** 認可を求める外部のサービス（OAUTH_CLIENTS で登録する。skiwi など）。redirectUri は完全一致で照合する。scopes は許すスコープ（read・write）。 */
final case class OAuthClient(
  id: String,
  secret: String,
  redirectUri: String,
  name: String,
  scopes: List[String] = List("read"))

type Res[A] = IO[Either[AppError, A]]
