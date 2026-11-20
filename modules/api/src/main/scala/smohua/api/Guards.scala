package smohua.api

import cats.data.Kleisli
import cats.effect.*
import org.http4s.*
import sbirds.http.{BodyLimit, Csrf, Errors, SecurityHeaders, Spa}
import sbirds.shared.AppError
import smohua.core.ErrorCode

/**
 * すべての要求に共通の守り:
 *  - 本文の大きさの上限（2MB）。大きすぎる本文でメモリを使い切らせない。
 *  - セキュリティのヘッダー（iframe の禁止（回答の画面は除く）・nosniff・Referrer・https なら HSTS）。
 *  - Cookie のセッションで状態を変える要求は、別のサイト（Origin が違う）からなら断る（同じ登録ドメインの別のサービスからの CSRF も防ぐ）。
 */
object Guards {

  private val JsonLimit: Long = 2L * 1024 * 1024

  /** 状態を変えない要求のメソッド（CSRF の確認・参照専用のキーの確認で使う）。 */
  val SafeMethods: Set[Method] = Set(Method.GET, Method.HEAD, Method.OPTIONS)

  /** 回答の画面（/f/…）は、ほかのサイトに iframe で埋め込めるようにする（フォームの設定の「埋め込み用の HTML」）。 */
  def embeddable(req: Request[IO]): Boolean =
    SafeMethods.contains(req.method) && req.pathInfo.segments.headOption.exists(_.decoded() == "f")

  def apply(app: HttpRoutes[IO], cfg: ApiConfig): HttpRoutes[IO] = {
    val https = cfg.publicUrl.startsWith("https://")
    // Origin（なければ Referer）と Sec-Fetch-Site を確かめる. "null"（サンドボックスの iframe など）も別のサイトとして扱う
    val csrf = Csrf.Policy(cfg.publicUrl, devLocalhost = cfg.devLogin).copy(safeMethods = Csrf.SafeWithOptions)
    /*
     * すべての応答に付けるセキュリティのヘッダー（応答が自分で付けたものは残す）。iframe に入れさせない（クリックジャッキング）。
     * ただし回答の画面（embed）は、どのサイトにも埋め込めるようにする（Cookie は SameSite=Lax なので、別のサイトの iframe の中ではログインしていない扱いになる）。
     */
    val policy = SecurityHeaders.Policy(
      hsts = SecurityHeaders.hsts(https),
      referrer = Some("same-origin"),
      csp = Some(SecurityHeaders.FrameCsp),
      keepExisting = true
    )
    val embedPolicy =
      policy.copy(frameOptions = None, csp = Some("frame-ancestors *; base-uri 'self'; object-src 'none'"))
    val limited = BodyLimit(JsonLimit, ErrorCode.BodyTooLarge)(app)
    SecurityHeaders.routesWith(req => if embeddable(req) then embedPolicy else policy)(
      Csrf.routes(csrf, Csrf.cookieAuth(cfg.cookieName), ErrorCode.CrossSiteRequest)(limited)
    )
  }

  /**
   * 処理の中で起きた例外の応答。本文が読めない・形が違う（MessageFailure）は 400（パーサーの文は返さない）、
   * それ以外は Errors.handle で 500（例外の文は返さず、コード付きでログにだけ残す）。HttpApp の一番外側に付ける。
   */
  def recovered(app: HttpApp[IO]): HttpApp[IO] = Errors.handle(
    Kleisli(req =>
      app.run(req).handleErrorWith {
        case e: MessageFailure => Errors.respond(AppError(ErrorCode.RequestBodyInvalid, None, Some(e)))
        case e => IO.raiseError(e)
      }
    ),
    ErrorCode.Unexpected
  )

  /**
   * 画面（index.html）の CSP。スクリプトは自分のサイトのものと、index.html に直接書いたもの（テーマの読み込み。ハッシュで許す）だけ。
   * skorimako のブラウザの観測（RUM）が設定されていれば、その rum.js の読み込みと送信も許す（rum はそのオリジン）。
   * スタイルはフォームの見た目（style 要素）と画面の部品の scoped な style のため 'unsafe-inline'。画像はアイコン（IdP の画像は外部の https）のため広めにする。
   * ヘッダーのロゴは IdP（sruru）の別のオリジンから来るので、http の開発環境でも出るよう IdP のオリジン（idp）も足す。
   * embed（回答の画面）は、どのサイトの iframe にも入れられる。
   */
  def pageCsp(indexHtml: String, embed: Boolean, rum: Option[String], idp: Option[String] = None): String = {
    val idpOrigin = idp.flatMap(u => scala.util.Try(java.net.URI.create(u)).toOption)
      .filter(u => u.getScheme != null && u.getAuthority != null).map(u => s"${u.getScheme}://${u.getAuthority}")
    val inline = Spa.inlineScriptHashes(indexHtml)
    List(
      "default-src 'self'",
      ("script-src 'self'" :: inline ++ rum.toList).mkString(" "),
      "style-src 'self' 'unsafe-inline'",
      ("img-src 'self' data: blob: https:" :: idpOrigin.toList).mkString(" "),
      ("connect-src 'self'" :: rum.toList).mkString(" "),
      "object-src 'none'",
      "base-uri 'self'",
      "form-action 'self'",
      if embed then "frame-ancestors *" else "frame-ancestors 'none'"
    ).mkString("; ")
  }
}
