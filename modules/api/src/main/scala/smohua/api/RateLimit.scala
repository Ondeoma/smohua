package smohua.api

import cats.effect.*
import com.comcast.ip4s.{Cidr, IpAddress}
import org.http4s.*
import sbirds.http.RateLimit
import smohua.core.ErrorCode

import scala.concurrent.duration.FiniteDuration

/**
 * IP ごとの回数の制限（1分あたり。0 なら制限しない）。ログインしなくても使える入口だけに掛ける。
 * auth はログイン（/auth/…）、public は回答する側の API（/api/public/…）、submit はそのうち回答の送信（匿名のフォームへの大量投稿を防ぐ）。
 * trustedProxies はリバースプロキシの IP か CIDR（RateLimit.proxy で読む）。そこから来た要求だけ X-Forwarded-For を信じる。
 */
final case class RateLimitConfig(authPerMinute: Int = 20,
                                 publicPerMinute: Int = 120,
                                 submitPerMinute: Int = 20,
                                 trustedProxies: List[Cidr[IpAddress]] = Nil)

/** smohua の回数の制限（どの要求をどのカウンタで数えるか）. 数え方は sbirds.http.RateLimit. */
object RateLimits {
  def apply(routes: HttpRoutes[IO], cfg: RateLimitConfig, clock: IO[FiniteDuration] = IO.realTime): HttpRoutes[IO] = {
    val auth = RateLimit.counter(cfg.authPerMinute, clock).toList
    val pub = RateLimit.counter(cfg.publicPerMinute, clock).toList
    val submit = RateLimit.counter(cfg.submitPerMinute, clock).toList
    RateLimit(routes, cfg.trustedProxies, ErrorCode.TooManyRequests) { req =>
      req.pathInfo.segments.map(_.decoded()).toList match {
        case "auth" :: _ => auth
        case "api" :: "public" :: "forms" :: _ :: "responses" :: Nil if req.method == Method.POST => pub ++ submit
        case "api" :: "public" :: _ => pub
        case _ => Nil
      }
    }
  }
}
