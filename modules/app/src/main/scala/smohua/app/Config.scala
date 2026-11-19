package smohua.app

import cats.effect.*
import cats.syntax.all.*
import smohua.api.*
import sbirds.auth.OidcConfig
import smohua.db.*
import sbirds.mail.SmtpConfig

/** 設定は環境変数から読む。 */
object Config {
  private def opt(
    name: String
  ): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
  private def int(
    name: String
  ): Option[Int] = opt(name).flatMap(_.toIntOption)
  private def enabled(
    name: String
  ): Boolean = opt(name).exists(_.equalsIgnoreCase("true"))
  private def required(
    name: String
  ): Either[String, String] = opt(name).toRight(s"環境変数 $name を設定してください")
  private def list(
    name: String
  ): List[String] = opt(name).toList.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty))

  /** OAUTH_CLIENTS: [{"id":"skiwi","secret":"…","redirectUri":"https://…/api/integrations/smohua/callback","name":"skiwi","scopes":["read"]}] */
  private def oauthClients: Either[String, List[smohua.api.OAuthClient]] =
    opt("OAUTH_CLIENTS").fold(Right(Nil)) { s =>
      io.circe.parser.parse(s).flatMap(_.as[List[io.circe.Json]]).left.map(_ =>
        "OAUTH_CLIENTS は JSON の配列で指定してください"
      ).flatMap(_.traverse { j =>
        val c = j.hcursor
        (c.get[String]("id"), c.get[String]("secret"), c.get[String]("redirectUri")).tupled.toOption
          .filter(
            (
              id,
              sec,
              uri
            ) =>
              id.nonEmpty && sec.length >= 16 &&
                (uri.startsWith("https://") ||
                  "^http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/".r.findFirstIn(uri).isDefined)
          )
          .map(
            (
              id,
              sec,
              uri
            ) =>
              smohua.api.OAuthClient(
                id,
                sec,
                uri,
                c.get[String]("name").getOrElse(id),
                c.get[List[String]]("scopes").toOption.map(_.filter(Set(
                  "read",
                  "write"
                ))).filter(_.nonEmpty).getOrElse(List("read"))
              )
          )
          .toRight("OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://。開発中は http://localhost:…/ も可）を指定してください")
      })
    }

  def load: IO[AppConfig] = IO.fromEither(
    (for {
      dbUrl <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      pub <- required("PUBLIC_URL")
      devLogin = enabled("DEV_LOGIN")
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
      clients <- oauthClients
      proxies <- list("TRUSTED_PROXIES").traverse(s =>
        sbirds.http.RateLimit.proxy(s).toRight(s"TRUSTED_PROXIES の「$s」は IP か CIDR で書いてください")
      )
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
          adminGroups = list("ADMIN_GROUPS").toSet,
          cookieSecure = base.startsWith("https://"),
          publicUrl = base,
          devLogin = devLogin,
          timezone = tz,
          oauthClients = clients,
          // STUI_URL・STUI_CLIENT_ID・STUI_CLIENT_SECRET（STUI_INTERNAL_URL はサーバーから呼ぶ URL）。stui に smohua をクライアントとして登録しておく
          stui = (opt("STUI_URL"), opt("STUI_CLIENT_ID"), opt("STUI_CLIENT_SECRET")).mapN(
            (
              u,
              id,
              sec
            ) =>
              smohua.api.LinkService(
                "stui",
                u.stripSuffix("/"),
                id,
                sec,
                opt("STUI_INTERNAL_URL").map(_.stripSuffix("/"))
              )
          ),
          mail = opt("SMTP_HOST").isDefined,
          // ログインしなくても使える入口の、IP ごとの1分あたりの回数（0 なら制限しない）
          rateLimit = RateLimitConfig(
            int("RATE_LIMIT_AUTH").getOrElse(20),
            int("RATE_LIMIT_PUBLIC").getOrElse(120),
            int("RATE_LIMIT_SUBMIT").getOrElse(20),
            proxies
          )
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
        nodeId = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName + "-" +
          java.util.UUID.randomUUID().toString.take(8))
      )
    }).left.map(new IllegalArgumentException(_))
  )
}
