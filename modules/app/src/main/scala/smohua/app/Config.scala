package smohua.app

import sbirds.http.Env.*
import sbirds.db.*
import cats.effect.*
import smohua.api.*
import sbirds.auth.OidcConfig
import sbirds.http.RateLimitConfig
import sbirds.mail.SmtpConfig

/** 設定は環境変数から読む。 */
object Config {

  /** カンマ区切りの一覧（グループの名前は空白を含むことがあるので、Env.list と違って空白では区切らない）。 */
  private def commaList(name: String): List[String] =
    opt(name).toList.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty))

  private def hostOf(url: String): Option[String] =
    scala.util.Try(java.net.URI.create(url).getHost).toOption.flatMap(Option(_)).map(_.toLowerCase)

  def load: IO[AppConfig] = IO.fromEither(
    (for {
      dbUrl <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      pub <- required("PUBLIC_URL")
      // 開発用のログインは名前だけでだれにでも（管理者にも）なれるので、手元（localhost）で開くときだけ（sbirds.http.Env.devLogin）
      devLogin <- devLogin(pub)
      _ <- Either.cond(!(devLogin && pub.startsWith("https://")), (), "DEV_LOGIN（開発用のログイン）は https の PUBLIC_URL では使えません")
      oidc <- (opt("OIDC_ISSUER"), opt("OIDC_CLIENT_ID"), opt("OIDC_CLIENT_SECRET")) match {
        case (Some(_), Some(_), Some(_)) if devLogin =>
          Left("DEV_LOGIN（開発用のログイン）は OIDC と同時には使えません。本番では DEV_LOGIN を外してください")
        case (Some(i), Some(c), Some(s)) =>
          Right(Some(OidcConfig(
            i,
            c,
            s,
            s"${pub.stripSuffix("/")}/auth/callback",
            opt("OIDC_GROUPS_CLAIM").getOrElse("groups")
          )))
        case (None, None, None) if devLogin => Right(None)
        case _ => Left("OIDC_ISSUER・OIDC_CLIENT_ID・OIDC_CLIENT_SECRET を設定してください（開発中は DEV_LOGIN=true で省略できます）")
      }
      secret <- opt("APP_SECRET") match {
        case Some(s) if s.length >= 16 => Right(s)
        case Some(_) => Left("APP_SECRET は16文字以上にしてください")
        case None if devLogin => Right("smohua-development-secret")
        case None => Left("APP_SECRET（16文字以上の秘密の値）を設定してください")
      }
      clients <- sbirds.oauth.OAuthClient.fromEnv(sbirds.oauth.OAuthClient.Rules(itemError =
        "OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://。開発中は http://localhost:…/ も可）を指定してください"
      ))
      rate <- RateLimitConfig.fromEnv()
      // stui との連携（STUI_URL・STUI_CLIENT_ID・STUI_CLIENT_SECRET。サーバーから呼ぶ URL は STUI_INTERNAL_URL）。stui に smohua をクライアントとして登録しておく
      stui <- sbirds.links.LinkConfig.fromEnv("STUI", "stui", "read write")
      // フォームの通知先（受信 Webhook・Webhook）へは、外のアドレスだけに送る。内部の相手は許可リストで許す（stui は自動で足す）
      notifyPolicy <- sbirds.net.NetGuard.Policy.allowList(
        list("NOTIFY_ALLOW_INTERNAL") ++ List("STUI_URL", "STUI_INTERNAL_URL").flatMap(opt).flatMap(hostOf)
      ).left.map(e => s"NOTIFY_ALLOW_INTERNAL: $e")
      tz <- opt("TIMEZONE").getOrElse("Asia/Tokyo") match {
        case z if scala.util.Try(java.time.ZoneId.of(z)).isSuccess => Right(z)
        case z => Left(s"TIMEZONE が正しくありません: $z（例: Asia/Tokyo）")
      }
    } yield {
      val base = pub.stripSuffix("/")
      AppConfig(
        port = int("HTTP_PORT").getOrElse(8080),
        db = DbConfig(dbUrl, dbUser, dbPass, int("DATABASE_POOL_SIZE").getOrElse(10)),
        oidc = oidc,
        api = ApiConfig(
          adminGroups = commaList("ADMIN_GROUPS").toSet,
          cookieSecure = base.startsWith("https://"),
          publicUrl = base,
          devLogin = devLogin,
          timezone = tz,
          oauthClients = clients,
          stui = stui,
          mail = opt("SMTP_HOST").isDefined,
          // ログインしなくても使える入口の、IP ごとの1分あたりの回数（0 なら制限しない）
          rateLimit = rate,
          // そのうち、回答の送信の回数
          rateLimitSubmit = int("RATE_LIMIT_SUBMIT").getOrElse(20)
        ),
        staticDir = opt("STATIC_DIR"),
        // メールの通知（SMTP）。送り先を skereru にすれば skereru に届く
        smtp = opt("SMTP_HOST").map { h =>
          val tls = opt("SMTP_TLS").getOrElse("starttls").toLowerCase
          SmtpConfig(
            h,
            int("SMTP_PORT").getOrElse(if tls == "ssl" then 465 else if tls == "none" then 25 else 587),
            opt("SMTP_USER"),
            opt("SMTP_PASSWORD"),
            tls,
            opt("MAIL_FROM").getOrElse(s"smohua@$h")
          )
        },
        appSecret = secret,
        notifyPolicy = notifyPolicy,
        nodeId = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName + "-" +
          java.util.UUID.randomUUID().toString.take(8))
      )
    }).left.map(new IllegalArgumentException(_))
  )
}
