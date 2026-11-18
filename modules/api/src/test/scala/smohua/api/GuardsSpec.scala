package smohua.api

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** 共通の守り（セキュリティのヘッダー・CSRF）と、IP ごとの回数の制限。DB は使わない。 */
class GuardsSpec extends CatsEffectSuite:

  private val cfg = ApiConfig(Set.empty, cookieSecure = true, "https://smohua.example.com")
  private val ok  = HttpRoutes.of[IO] { case _ => Ok("ok") }

  private def header(r: Response[IO], k: String): Option[String] = r.headers.get(CIString(k)).map(_.head.value)

  test("ヘッダー: ふつうの画面は iframe を禁じ、回答の画面（/f/…）だけ埋め込める") {
    val app = Guards(ok, cfg).orNotFound
    for
      top  <- app.run(Request[IO](Method.GET, uri"/forms/1"))
      fill <- app.run(Request[IO](Method.GET, uri"/f/abc?embed=1"))
      api  <- app.run(Request[IO](Method.GET, uri"/api/public/forms/abc"))
    yield
      assertEquals(header(top, "X-Frame-Options"), Some("DENY"))
      assert(header(top, "Content-Security-Policy").exists(_.contains("frame-ancestors 'none'")))
      assertEquals(header(fill, "X-Frame-Options"), None)
      assert(header(fill, "Content-Security-Policy").exists(_.contains("frame-ancestors *")))
      assertEquals(header(api, "X-Frame-Options"), Some("DENY"))
      assertEquals(header(top, "Strict-Transport-Security"), Some("max-age=31536000"))
  }

  test("CSRF: Cookie のセッションで、別のサイトからの更新の要求は断る") {
    val app = Guards(ok, cfg).orNotFound
    def post(origin: String) = Request[IO](Method.POST, uri"/api/forms").addCookie(cfg.cookieName, "x").putHeaders("Origin" -> origin)
    for
      evil <- app.run(post("https://evil.example"))
      same <- app.run(post("https://smohua.example.com"))
      nul  <- app.run(post("null"))
    yield
      assertEquals(evil.status, Status.Forbidden)
      assertEquals(same.status, Status.Ok)
      assertEquals(nul.status, Status.Forbidden)
  }

  private def from(ip: String, method: Method, path: Uri, xff: Option[String] = None): Request[IO] =
    val r = Request[IO](method, path).withAttribute(Request.Keys.ConnectionInfo,
      Request.Connection(SocketAddress(ipv4"10.0.0.2", port"8080"), SocketAddress(IpAddress.fromString(ip).get, port"50000"), false))
    xff.fold(r)(v => r.putHeaders("X-Forwarded-For" -> v))

  test("回数の制限: 匿名のフォームへの回答の送信は、IP ごとに1分あたりの上限まで") {
    val clock = IO.pure(10.minutes)
    val app = RateLimit(ok, RateLimitConfig(authPerMinute = 2, publicPerMinute = 100, submitPerMinute = 3), clock).orNotFound
    val submit = uri"/api/public/forms/abc/responses"
    for
      rs    <- List.fill(4)(app.run(from("203.0.113.5", Method.POST, submit))).sequence
      other <- app.run(from("203.0.113.6", Method.POST, submit))
      view  <- app.run(from("203.0.113.5", Method.GET, uri"/api/public/forms/abc"))
      forms <- List.fill(5)(app.run(from("203.0.113.5", Method.GET, uri"/api/forms"))).sequence
      login <- List.fill(3)(app.run(from("203.0.113.7", Method.GET, uri"/auth/login"))).sequence
    yield
      assertEquals(rs.map(_.status), List(Status.Ok, Status.Ok, Status.Ok, Status.TooManyRequests))
      assert(header(rs.last, "Retry-After").isDefined)
      assertEquals(other.status, Status.Ok) // 別の IP は別に数える
      assertEquals(view.status, Status.Ok)  // 見るだけなら、送信の上限とは別
      assert(forms.forall(_.status == Status.Ok)) // ログインした人の API には掛けない
      assertEquals(login.map(_.status), List(Status.Ok, Status.Ok, Status.TooManyRequests))
  }

  test("回数の制限: X-Forwarded-For は、信じるプロキシから来たときだけ使う") {
    val proxy = RateLimitConfig.proxy("10.0.0.0/8").toList
    val req = (ip: String, xff: String) => from(ip, Method.GET, uri"/auth/login", Some(xff))
    // プロキシ経由なら、その左の IP（本当の相手）で数える
    assertEquals(RateLimit.clientKey(req("10.1.1.1", "198.51.100.9, 10.2.2.2"), proxy), Some("198.51.100.9"))
    // 直接つないできた相手が勝手に書いた X-Forwarded-For は使わない
    assertEquals(RateLimit.clientKey(req("203.0.113.5", "198.51.100.9"), proxy), Some("203.0.113.5"))
  }

  test("画面の CSP: index.html のインラインのスクリプトはハッシュで許し、RUM のオリジンを足す。回答の画面だけ埋め込める") {
    val html  = "<html><head><script>var t = 1</script><script type=\"module\" src=\"/assets/a.js\"></script></head></html>"
    val hash  = java.util.Base64.getEncoder.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest("var t = 1".getBytes("UTF-8")))
    val page  = Guards.pageCsp(html, embed = false, Some("https://rum.example.com"))
    val embed = Guards.pageCsp(html, embed = true, None)
    assert(page.contains(s"script-src 'self' 'sha256-$hash' https://rum.example.com;"), page)
    assert(page.contains("connect-src 'self' https://rum.example.com;"), page)
    assert(page.contains("frame-ancestors 'none'") && !page.contains("unsafe-eval"), page)
    assert(embed.contains("frame-ancestors *") && embed.contains(s"script-src 'self' 'sha256-$hash';"), embed)
    assert(Guards.embeddable(Request[IO](Method.GET, uri"/f/abc")) && !Guards.embeddable(Request[IO](Method.GET, uri"/forms/abc")))
  }

  test("開発用のオリジン（localhost の開発サーバー）は DEV_LOGIN のときだけ自分のサイトとして扱う") {
    assert(cfg.copy(devLogin = true).devOrigin("http://localhost:5198/settings"))
    assert(!cfg.devOrigin("http://localhost:5198/settings"))
    assert(!cfg.copy(devLogin = true).devOrigin("http://localhost.evil.example/"))
  }
