package smohua.api

import cats.effect.*
import sbirds.http.RateLimitConfig
import sbirds.shared.AppError

import scala.concurrent.duration.*

final case class ApiConfig(adminGroups: Set[String],
                           cookieSecure: Boolean,
                           publicUrl: String,
                           sessionTtl: FiniteDuration = 14.days,
                           cookieName: String = "smohua_session",
                           devLogin: Boolean = false,
                           timezone: String = "Asia/Tokyo",
                           version: String = "0.1.0",
                           oauthClients: List[OAuthClient] = Nil,
                           stui: Option[sbirds.links.LinkConfig] = None,
                           mail: Boolean = false,
                           rateLimit: RateLimitConfig = RateLimitConfig(),
                           /** 回答の送信（匿名のフォームへの大量投稿を防ぐ）の、IP ごとの1分あたりの回数（0 なら制限しない）。 */
                           rateLimitSubmit: Int = 20) {

  /** 開発中（DEV_LOGIN）だけ、画面の開発サーバー（http://localhost:…・http://127.0.0.1:…）も自分のサイトとして扱う。 */
  def devOrigin(url: String): Boolean = devLogin &&
    (url.startsWith("http://localhost:") || url.startsWith("http://127.0.0.1:"))
}

/** 認可を求める外部のサービス（OAUTH_CLIENTS で登録する。skiwi など）。redirectUri は完全一致で照合する。scopes は許すスコープ（read・write）。 */
type OAuthClient = sbirds.oauth.OAuthClient
val OAuthClient = sbirds.oauth.OAuthClient

type Res[A] = IO[Either[AppError, A]]
