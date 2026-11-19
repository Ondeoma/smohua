package smohua.api

import sbirds.http.RateLimit
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.*
import munit.FunSuite
import org.http4s.*
import org.http4s.implicits.*
import sbirds.mail.Mail

/** 外とのやり取りの守り（メールのヘッダー・例外の応答・IP）。DB は使わない。 */
class HardeningSpec extends FunSuite {

  test("メール: 件名・宛名の改行は空白にする（ヘッダーの差し込みを防ぐ）") {
    assertEquals(Mail.headerValue("「題名」に回答\r\nBcc: evil@example.com"), "「題名」に回答  Bcc: evil@example.com")
    assertEquals(Mail.headerValue("  山田\n太郎 "), "山田 太郎")
  }

  test("例外の応答: 読めない本文は 400、それ以外は 500 で例外の文は出さない") {
    def run(e: Throwable) = Guards.recovered(HttpApp[IO](_ => IO.raiseError(e))).run(Request[IO]()).flatMap(r =>
      r.bodyText.compile.string.map(r.status -> _)
    ).unsafeRunSync()
    val bad = run(MalformedMessageBodyFailure("Invalid JSON: unexpected token at 1:1"))
    assertEquals(bad._1, Status.BadRequest)
    assert(!bad._2.contains("unexpected"), bad._2)
    val boom = run(new RuntimeException("password=secret"))
    assertEquals(boom._1, Status.InternalServerError)
    assert(!boom._2.contains("secret"), boom._2)
  }

  test("観測の IP は信じるプロキシの設定に合わせる（IPv6 も /64 にまとめない）") {
    val proxy = List(RateLimit.proxy("10.0.0.0/8").get)
    def req(peer: String, xff: String) =
      Request[IO](uri = uri"/").withAttribute(
        Request.Keys.ConnectionInfo,
        Request.Connection(
          SocketAddress(ip"127.0.0.1", port"8080"),
          SocketAddress(IpAddress.fromString(peer).get, port"5000"),
          secure = false
        )
      )
        .putHeaders("X-Forwarded-For" -> xff)
    assertEquals(RateLimit.clientIp(req("10.1.1.1", "2001:db8::1:2"), proxy), Some("2001:db8::1:2"))
    assertEquals(RateLimit.clientKey(req("10.1.1.1", "2001:db8::1:2"), proxy), Some("2001:db8::/64"))
    // 信じないプロキシから来た X-Forwarded-For は読まない
    assertEquals(RateLimit.clientIp(req("203.0.113.5", "198.51.100.9"), proxy), Some("203.0.113.5"))
  }
}
