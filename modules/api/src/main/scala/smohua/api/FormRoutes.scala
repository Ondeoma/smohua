package smohua.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import smohua.core.*
import smohua.db.*
import smohua.shared.*

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** フォーム・回答・集計・CSV、回答する側の API、画面内の通知。 */
final class FormRoutes(
  forms: FormStore,
  svc: FormService,
  val sessions: SessionAuth) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // ---------------------------------------------------------------- フォーム（作る側）
    case req @ GET -> Root / "api" / "forms" => getForms(req)
    case req @ POST -> Root / "api" / "forms" => postForms(req)
    case req @ POST -> Root / "api" / "forms" / "lookup" => postFormsLookup(req)
    case req @ GET -> Root / "api" / "forms" / UuidVar(id) => getForm(req, id)
    case req @ PATCH -> Root / "api" / "forms" / UuidVar(id) => patchForm(req, id)
    case req @ DELETE -> Root / "api" / "forms" / UuidVar(id) => deleteForm(req, id)
    case req @ POST -> Root / "api" / "forms" / UuidVar(id) / "duplicate" => postFormDuplicate(req, id)
    case req @ GET -> Root / "api" / "forms" / UuidVar(id) / "responses" => getFormResponses(req, id)
    case req @ GET -> Root / "api" / "forms" / UuidVar(id) / "responses.csv" => getFormResponsesCsv(req, id)
    case req @ DELETE -> Root / "api" / "forms" / UuidVar(id) / "responses" / UuidVar(rid) =>
      deleteFormResponse(req, id, rid)
    case req @ GET -> Root / "api" / "forms" / UuidVar(id) / "summary" => getFormSummary(req, id)
    case req @ GET -> Root / "api" / "public" / "forms" / UuidVar(id) => getPublicForm(req, id)
    case req @ POST -> Root / "api" / "public" / "forms" / UuidVar(id) / "responses" => postPublicFormResponses(req, id)
    case req @ GET -> Root / "api" / "notifications" => getNotifications(req)
    case req @ GET -> Root / "api" / "notifications" / "unread" => getNotificationsUnread(req)
    case req @ POST -> Root / "api" / "notifications" / "read" => postNotificationsRead(req)
  }

  private def getForms(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      forms.list(
        s.user.id,
        all = s.canAdmin && param(req, "all").contains("true"),
        param(req, "q"),
        intParam(req, "limit", 200, 500)
      ).flatMap(Ok(_))
    )

  private def postForms(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => body[CreateFormReq](req)(r => respond(svc.create(s, r))(Created(_))))

  private def postFormsLookup(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[Json](req) { j =>
        val ids = j.hcursor.get[List[String]]("ids").getOrElse(Nil).flatMap(uuidOpt)
        if ids.size > 100 then err(ErrorCode.LookupTooManyIds)
        else svc.lookup(s, ids).flatMap(fs => Ok(Json.obj("forms" -> fs.asJson)))
      }
    )

  private def getForm(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withForm(req, id)(
      (
        s,
        f
      ) => svc.dto(s, f).flatMap(Ok(_))
    )

  private def patchForm(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s => body[UpdateFormReq](req)(r => okJson(svc.update(s, id, r))))

  private def deleteForm(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s => respond(svc.delete(s, id))(_ => NoContent()))

  private def postFormDuplicate(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s => respond(svc.duplicate(s, id))(Created(_)))

  private def getFormResponses(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withForm(req, id)(
      (
        s,
        _
      ) =>
        forms.markFormRead(s.user.id, id).whenA(s.isSession) *>
          forms.responses(
            id,
            param(req, "before").flatMap(_.toLongOption),
            intParam(req, "limit", 50, 500)
          ).flatMap(Ok(_))
    )

  private def getFormResponsesCsv(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withForm(req, id)(csv)

  private def deleteFormResponse(
    req: Request[IO],
    id: UUID,
    rid: UUID
  ): IO[Response[IO]] =
    authed(req)(s => respond(svc.deleteResponse(s, id, rid))(_ => NoContent()))

  private def getFormSummary(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withForm(req, id)(
      (
        _,
        f
      ) => svc.summary(f).flatMap(Ok(_))
    )

  private def getPublicForm(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap(s => okJson(svc.public(s, id)))

  private def postPublicFormResponses(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap(s => body[SubmitReq](req)(r => respond(svc.submit(s, id, r))(Created(_))))

  private def getNotifications(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      forms.notifications(
        s.user.id,
        param(req, "unread").contains("true"),
        intParam(req, "limit", 50, 200)
      ).flatMap(Ok(_))
    )

  private def getNotificationsUnread(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => forms.unread(s.user.id).flatMap(n => Ok(Json.obj("unread" -> n.asJson))))

  private def postNotificationsRead(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[Json](req) { j =>
        // ids が読めないときに「すべて既読」にならないよう、指定があれば UUID の配列であることを確かめる
        j.hcursor.downField("ids").focus.filterNot(_.isNull).fold[Either[
          Unit,
          List[UUID]
        ]](Right(Nil))(_.as[List[UUID]].left.map(_ => ())) match {
          case Left(_) => err(ErrorCode.NotificationIdsInvalid)
          case Right(ids) => forms.markRead(s.user.id, ids.take(500)) *> NoContent()
        }
      }
    )

  /** フォームを見られることを確認してから処理する。 */
  private def withForm(
    req: Request[IO],
    id: UUID
  )(
    f: (AuthSession, FormRow) => IO[Response[IO]]
  ): IO[Response[IO]] =
    authed(req)(s => respond(svc.access(s, id))(row => f(s, row)))

  private val CsvPage = 1000

  private def csv(
    s: AuthSession,
    f: FormRow
  ): IO[Response[IO]] = {
    val zone = scala.util.Try(ZoneId.of(s.user.timezone)).getOrElse(ZoneId.of("Asia/Tokyo"))
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
    val qs = f.questions
    // 古い順に、1000件ずつ読みながら流す（Excel で開けるよう BOM を付ける）
    def page(
      after: Option[Long]
    ): fs2.Stream[IO, String] =
      fs2.Stream.eval(forms.responsesAfter(f.id, after, CsvPage)).flatMap { rs =>
        val lines = rs.map(r =>
          Forms.csvLine(List(r.seq.toString, fmt.format(r.createdAt), r.respondent.fold("")(_.name)) ++
            qs.map(Answers.of(r.answers, _)))
        )
        fs2.Stream.emits(lines) ++ (if rs.size < CsvPage then fs2.Stream.empty else page(rs.lastOption.map(_.seq)))
      }
    val body = (fs2.Stream.emit("﻿" + Forms.csvLine(Forms.csvHeader(qs))) ++ page(None)).through(fs2.text.utf8.encode)
    val name = java.net.URLEncoder.encode(f.title.take(60) + ".csv", "UTF-8").replace("+", "%20")
    IO.pure(Response[IO](Status.Ok).withEntity(body).putHeaders(
      `Content-Type`(MediaType.text.csv, Charset.`UTF-8`),
      "Content-Disposition" -> s"attachment; filename=\"responses.csv\"; filename*=UTF-8''$name",
      "Cache-Control" -> "no-store"
    ))
  }

}
