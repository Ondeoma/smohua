package smohua.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import smohua.core.AuthSession
import smohua.db.*
import smohua.shared.*

import java.util.UUID

/** フォームの通知先（受信 Webhook・Webhook・メールアドレス）. 見る・変えるのは、持ち主と管理者だけ. */
final class FormTargetRoutes(svc: FormService,
                             targets: TargetService,
                             activity: ActivityStore,
                             val sessions: SessionAuth)
  extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "forms" / UuidVar(id) / "targets" => getTargets(req, id)
    case req @ POST -> Root / "api" / "forms" / UuidVar(id) / "targets" => postTargets(req, id)
    case req @ PUT -> Root / "api" / "forms" / UuidVar(id) / "targets" / UuidVar(tid) => putTarget(req, id, tid)
    case req @ DELETE -> Root / "api" / "forms" / UuidVar(id) / "targets" / UuidVar(tid) => deleteTarget(req, id, tid)
    case req @ POST -> Root / "api" / "forms" / UuidVar(id) / "targets" / UuidVar(tid) / "test" =>
      postTargetTest(req, id, tid)
  }

  private def getTargets(req: Request[IO], id: UUID): IO[Response[IO]] =
    owned(req, id, write = false)((_, f) => targets.list(f).flatMap(Ok(_)))

  private def postTargets(req: Request[IO], id: UUID): IO[Response[IO]] =
    owned(req, id, write = true)((s, f) =>
      body[FormTargetReq](req)(r =>
        respond(targets.create(f, r))(t => audit(s, "form.target.create", f, t) *> Created(t))
      )
    )

  private def putTarget(req: Request[IO], id: UUID, tid: UUID): IO[Response[IO]] =
    owned(req, id, write = true)((s, f) =>
      body[FormTargetReq](req)(r =>
        respond(targets.update(f, tid, r))(t => audit(s, "form.target.update", f, t) *> Ok(t))
      )
    )

  private def deleteTarget(req: Request[IO], id: UUID, tid: UUID): IO[Response[IO]] =
    owned(req, id, write = true)((s, f) =>
      respond(targets.delete(f, tid))(_ =>
        activity.audit(
          Some(s.user.id),
          s.viaString,
          "form.target.delete",
          Some(f.id.toString),
          Json.obj("target" -> tid.asJson)
        ) *> NoContent()
      )
    )

  private def postTargetTest(req: Request[IO], id: UUID, tid: UUID): IO[Response[IO]] =
    owned(req, id, write = true)((s, f) => respond(targets.test(f, tid, s.user.name))(_ => NoContent()))

  private def owned(req: Request[IO], id: UUID, write: Boolean)(
    f: (AuthSession, FormRow) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => respond(svc.ownerAccess(s, id, write))(row => f(s, row)))

  /** 監査ログには送り先のホストかアドレスを残す（受信 Webhook の URL は鍵を兼ねるので、URL そのものは残さない）. */
  private def audit(s: AuthSession, action: String, f: FormRow, t: FormTargetDto): IO[Unit] =
    activity.audit(
      Some(s.user.id),
      s.viaString,
      action,
      Some(f.id.toString),
      Json.obj(
        "target" -> t.id.asJson,
        "kind" -> t.kind.asJson,
        "to" -> t.url.flatMap(u => Uri.fromString(u).toOption.flatMap(_.host).map(_.value)).orElse(t.address).asJson,
        "events" -> t.events.asJson
      )
    )
}
