package smohua.api

import cats.data.OptionT
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.{Cidr, IpAddress, Ipv6Address}
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.typelevel.ci.CIString
import smohua.shared.ErrorDto

import scala.concurrent.duration.FiniteDuration

/**
 * IP ごとの回数の制限（1分あたり。0 なら制限しない）。ログインしなくても使える入口だけに掛ける。
 * auth はログイン（/auth/…）、public は回答する側の API（/api/public/…）、submit はそのうち回答の送信（匿名のフォームへの大量投稿を防ぐ）。
 * trustedProxies はリバースプロキシの IP か CIDR。そこから来た要求だけ X-Forwarded-For を信じる。
 */
final case class RateLimitConfig(authPerMinute: Int = 20, publicPerMinute: Int = 120, submitPerMinute: Int = 20,
    trustedProxies: List[Cidr[IpAddress]] = Nil)

object RateLimitConfig:
  /** IP（10.0.0.1）か CIDR（10.0.0.0/8）。 */
  def proxy(s: String): Option[Cidr[IpAddress]] =
    Cidr.fromString(s).orElse(IpAddress.fromString(s).map(ip => Cidr(ip, if ip.fold(_ => true, _ => false) then 32 else 128)))

/**
 * 1分ごとの窓で数える（窓が変わったら数え直すので、覚える量はその1分に来た IP の数まで）。
 * 各ノードのメモリで数えるので、複数のノードで動かすと上限はノードの数だけ増える。
 */
final class RateCounter(limit: Int, clock: IO[FiniteDuration] = IO.realTime):
  private val counts = Ref.unsafe[IO, (Long, Map[String, Int])]((0L, Map.empty))

  /** 1回数える。上限を超えたら、次の窓までの秒数。 */
  def hit(key: String): IO[Option[Long]] = clock.flatMap { now =>
    val window = now.toMinutes
    counts.modify { (w, m0) =>
      val m = if w == window then m0 else Map.empty
      val n = m.getOrElse(key, 0) + 1
      ((window, m.updated(key, n)), Option.when(n > limit)(60 - now.toSeconds % 60))
    }
  }

object RateLimit:
  val Message = "短い時間に要求が多すぎます。しばらく待ってからもう一度試してください"

  /**
   * 要求を送ってきた人の IP。直接つないできた相手から X-Forwarded-For を右（近いほう）へたどり、
   * 信じるプロキシでない最初の IP にする（クライアントが勝手に書いた左側の値は使わない）。相手が分からなければ None。
   */
  def clientIp(req: Request[IO], trusted: List[Cidr[IpAddress]]): Option[String] =
    req.remote.map(_.host).map { peer =>
      val forwarded = req.headers.get(CIString("X-Forwarded-For")).toList.flatMap(_.toList)
        .flatMap(_.value.split(',')).map(_.trim).filter(_.nonEmpty).reverse
      val hops = peer.toString :: forwarded
      hops.find(h => IpAddress.fromString(h).forall(ip => !trusted.exists(_.contains(ip)))).getOrElse(hops.last)
    }

  /** 数えるときの鍵（clientIp。IPv6 は /64 ごとにまとめる。1人が大量のアドレスを使い分けられるため）。 */
  def clientKey(req: Request[IO], trusted: List[Cidr[IpAddress]]): Option[String] =
    clientIp(req, trusted).map { client =>
      IpAddress.fromString(client).map(_.collapseMappedV4).fold(client)(_.fold(_.toString, v6 =>
        Ipv6Address.fromBytes(v6.toBytes.take(8) ++ Array.fill[Byte](8)(0)).fold(v6.toString)(_.toString + "/64")))
    }

  def apply(routes: HttpRoutes[IO], cfg: RateLimitConfig, clock: IO[FiniteDuration] = IO.realTime): HttpRoutes[IO] =
    val auth   = new RateCounter(cfg.authPerMinute, clock)
    val pub    = new RateCounter(cfg.publicPerMinute, clock)
    val submit = new RateCounter(cfg.submitPerMinute, clock)
    def on(c: RateCounter, perMinute: Int): List[RateCounter] = if perMinute > 0 then List(c) else Nil
    HttpRoutes[IO] { req =>
      val counters = req.pathInfo.segments.map(_.decoded()).toList match
        case "auth" :: _ => on(auth, cfg.authPerMinute)
        case "api" :: "public" :: "forms" :: _ :: "responses" :: Nil if req.method == Method.POST =>
          on(pub, cfg.publicPerMinute) ++ on(submit, cfg.submitPerMinute)
        case "api" :: "public" :: _ => on(pub, cfg.publicPerMinute)
        case _                      => Nil
      clientKey(req, cfg.trustedProxies).filter(_ => counters.nonEmpty) match
        case None => routes(req)
        case Some(key) =>
          OptionT.liftF(counters.traverse(_.hit(key)).map(_.flatten.maxOption)).flatMap {
            case None => routes(req)
            case Some(wait) =>
              OptionT.pure(Response[IO](Status.TooManyRequests).withEntity(ErrorDto(Message)).putHeaders("Retry-After" -> wait.toString))
          }
    }
