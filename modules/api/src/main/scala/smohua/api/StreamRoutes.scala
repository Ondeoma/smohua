package smohua.api

import cats.effect.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import smohua.core.ErrorCode

import java.util.UUID
import scala.concurrent.duration.*

/** 画面に知らせる出来事（フォームの変更・回答、ユーザーへの通知）。watchers はそのフォームの持ち主と編集できる人。 */
enum FeedItem {
  case Changed(
    formId: UUID,
    eventId: Long,
    kind: String,
    watchers: Set[UUID])
  case Notified(
    userId: UUID)
}

final class StreamRoutes(
  feed: EventFeed,
  val sessions: SessionAuth) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    /**
     * 画面への知らせ。自分のフォーム（持ち主・編集できるもの）に変更や回答があれば change（{form, kind}）、
     * 自分への通知があれば notify を送る。届いたら画面が読み直す。
     */
    case req @ GET -> Root / "api" / "stream" => getStream(req)
  }

  private def getStream(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req) { s =>
      feed.subscribe(s.user.id).flatMap {
        case None => err(ErrorCode.TooManyStreams)
        case Some(feedItems) =>
          val events = feedItems.collect {
            case FeedItem.Changed(f, id, kind, ws) if ws.contains(s.user.id) =>
              sse("change", Json.obj("latest" -> id.asJson, "form" -> f.asJson, "kind" -> kind.asJson))
            case FeedItem.Notified(u) if u == s.user.id => sse("notify", Json.obj())
          }
          // 接続が切られないよう 20 秒ごとに空の行を送る
          val ping = Stream.awakeEvery[IO](20.seconds).as(ServerSentEvent(comment = Some("ping")))
          // ログアウト・ユーザーの無効化・キーの失効の後は流し続けない（1分ごとに確かめて切る）
          val revoked =
            Stream.awakeEvery[IO](60.seconds).evalMap(_ => sessions.alive(req).map(!_).handleError(_ => false))
          val body =
            (Stream.emit(ServerSentEvent(retry = Some(5.seconds), comment = Some("hello"))) ++
              events.merge(ping)).interruptWhen(revoked)
          Ok(body.through(st =>
            Stream.bracket(sbirds.observe.Metrics.upDown("smohua.sse.connections", 1))(_ =>
              sbirds.observe.Metrics.upDown("smohua.sse.connections", -1)
            ) >> st
          ))
            .map(_.putHeaders("Cache-Control" -> "no-cache", "X-Accel-Buffering" -> "no"))
      }
    }

  private def sse(
    kind: String,
    data: Json
  ): ServerSentEvent = ServerSentEvent(data = Some(data.noSpaces), eventType = Some(kind))

}
