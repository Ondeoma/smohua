package smohua.api

import cats.data.{Kleisli, OptionT}
import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString
import org.http4s.server.middleware.EntityLimiter
import sbirds.http.Errors
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

  private def originOf(u: String): String = Uri.fromString(u).toOption.map(x =>
    s"${x.scheme.fold("")(_.value)}://${x.authority.fold("")(_.renderString)}"
  ).getOrElse(u)

  /** 回答の画面（/f/…）は、ほかのサイトに iframe で埋め込めるようにする（フォームの設定の「埋め込み用の HTML」）。 */
  def embeddable(req: Request[IO]): Boolean =
    SafeMethods.contains(req.method) && req.pathInfo.segments.headOption.exists(_.decoded() == "f")

  def apply(app: HttpRoutes[IO], cfg: ApiConfig): HttpRoutes[IO] = {
    val allowed = originOf(cfg.publicUrl).toLowerCase
    val https = cfg.publicUrl.startsWith("https://")
    def foreign(req: Request[IO]): Boolean =
      !SafeMethods.contains(req.method) &&
        req.headers.get[Authorization].isEmpty &&
        req.cookies.exists(_.name == cfg.cookieName) &&
        req.headers.get(CIString("Origin")).map(_.head.value.trim.toLowerCase).exists { o =>
          // "null"（サンドボックスの iframe など）も別のサイトとして扱う
          o != allowed && !cfg.devOrigin(o)
        }
    HttpRoutes[IO] { req =>
      val res =
        if foreign(req) then
          OptionT.liftF(Errors.respond(ErrorCode.CrossSiteRequest))
        else if req.contentLength.exists(_ > JsonLimit) then
          OptionT.liftF(Errors.respond(ErrorCode.BodyTooLarge))
        else EntityLimiter.httpRoutes(app, JsonLimit).run(req)
      res.map(secure(_, https, embeddable(req)))
    }
  }

  /**
   * 処理の中で起きた例外の応答。大きすぎる本文は 413、本文が読めない・形が違う（MessageFailure）は 400（パーサーの文は返さない）、
   * それ以外は Errors.handle で 500（例外の文は返さず、コード付きでログにだけ残す）。HttpApp の一番外側に付ける。
   */
  def recovered(app: HttpApp[IO]): HttpApp[IO] = Errors.handle(
    Kleisli(req =>
      app.run(req).handleErrorWith {
        case _: EntityLimiter.EntityTooLarge => Errors.respond(ErrorCode.BodyTooLarge)
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
    val inline = "(?s)<script>(.*?)</script>".r.findAllMatchIn(indexHtml).map(m =>
      s"'sha256-${java.util.Base64.getEncoder.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(m.group(1).getBytes("UTF-8")))}'"
    )
    List(
      "default-src 'self'",
      ("script-src 'self'" :: inline.toList ++ rum.toList).mkString(" "),
      "style-src 'self' 'unsafe-inline'",
      ("img-src 'self' data: blob: https:" :: idpOrigin.toList).mkString(" "),
      ("connect-src 'self'" :: rum.toList).mkString(" "),
      "object-src 'none'",
      "base-uri 'self'",
      "form-action 'self'",
      if embed then "frame-ancestors *" else "frame-ancestors 'none'"
    ).mkString("; ")
  }

  /**
   * すべての応答に付けるセキュリティのヘッダー（応答が自分で付けたものは残す）。iframe に入れさせない（クリックジャッキング）。
   * ただし回答の画面（embed）は、どのサイトにも埋め込めるようにする（Cookie は SameSite=Lax なので、別のサイトの iframe の中ではログインしていない扱いになる）。
   */
  private def secure(r: Response[IO], https: Boolean, embed: Boolean): Response[IO] = {
    val frame =
      if embed then List("Content-Security-Policy" -> "frame-ancestors *; base-uri 'self'; object-src 'none'")
      else
        List(
          "X-Frame-Options" -> "DENY",
          "Content-Security-Policy" -> "frame-ancestors 'none'; base-uri 'self'; object-src 'none'"
        )
    val base = frame ++ List(
      "X-Content-Type-Options" -> "nosniff",
      "Referrer-Policy" -> "same-origin"
    ) ++ (if https then List("Strict-Transport-Security" -> "max-age=31536000") else Nil)
    base.foldLeft(r) { case (acc, (k, v)) =>
      if acc.headers.get(CIString(k)).isDefined then acc else acc.putHeaders(k -> v)
    }
  }
}
