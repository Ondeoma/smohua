package smohua.api

import cats.effect.*
import org.http4s.*
import sbirds.http.RateLimit.Path
import sbirds.http.RateLimitConfig
import smohua.core.ErrorCode

import scala.concurrent.duration.FiniteDuration

/**
 * smohua の回数の制限（どの要求をどのカウンタで数えるか）.
 * 共通の数え方（/auth/…・/oauth/authorize は auth、/oauth/token・/oauth/revoke・/api/public/… は public の枠）は
 * sbirds-lib の RateLimit.standard. ここでは、smohua だけの違いを決める.
 * 回答する側の API（/api/public/…）のうち回答の送信は、public に加えて submit（RATE_LIMIT_SUBMIT）でも数える（匿名のフォームへの大量投稿を防ぐ）.
 */
object RateLimits {

  def apply(routes: HttpRoutes[IO],
            cfg: RateLimitConfig,
            submitPerMinute: Int,
            clock: IO[FiniteDuration] = IO.realTime): HttpRoutes[IO] =
    sbirds.http.RateLimit.standard(routes, cfg, ErrorCode.TooManyRequests, clock) { b =>
      val submit = b.counter(submitPerMinute)
      {
        case req @ Path("api" :: "public" :: "forms" :: _ :: "responses" :: Nil) if req.method == Method.POST =>
          b.public ++ submit
      }
    }
}
