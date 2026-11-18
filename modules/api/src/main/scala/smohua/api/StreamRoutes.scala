package smohua.api

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import cats.effect.std.Queue
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import smohua.db.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** 画面に知らせる出来事（フォームの変更・回答、ユーザーへの通知）。watchers はそのフォームの持ち主と編集できる人。 */
enum FeedItem:
  case Changed(formId: UUID, eventId: Long, kind: String, watchers: Set[UUID])
  case Notified(userId: UUID)

/**
 * 変更の配信（SSE）。各ノードが0.5秒ごとに新しい変更履歴（form_event）と通知を DB で確かめ、そのノードにつないでいる画面へ知らせる
 * （ノードどうしの連絡は不要。DB がどのノードの変更も持っているため）。
 * 接続ごとに上限付きのキューを持ち、あふれたら捨てる（受け取らない接続があっても、ほかの人への配信を止めない。
 * 画面はつなぎ直したとき・定期的に読み直して追いつく）。ユーザーごとの接続数にも上限がある。
 */
final class EventFeed private (forms: FormStore,
    subs: Ref[IO, Map[Long, Queue[IO, FeedItem]]], perUser: Ref[IO, Map[UUID, Int]], ids: Ref[IO, Long]):

  private val MaxPerUser = 20

  private def publish(item: FeedItem): IO[Unit] = subs.get.flatMap(_.values.toList.traverse_(_.tryOffer(item)))

  def loop: IO[Nothing] =
    (forms.latestEvent, IO.realTimeInstant).tupled.flatMap { (start, now) =>
      def step(last: Long, since: Instant): IO[(Long, Instant)] =
        for
          evs <- forms.eventsAfter(last)
          // つないでいる画面がなければ、だれに配るかは調べない
          any <- subs.get.map(_.nonEmpty)
          // 同じフォームの変更が続いても、受け取る人はまとめて1回で読む
          ws  <- if any then forms.watchersOf(evs.map(_._2).distinct) else IO.pure(Map.empty[UUID, Set[UUID]])
          _   <- IO.whenA(any)(evs.traverse_((id, f, kind) => publish(FeedItem.Changed(f, id, kind, ws.getOrElse(f, Set.empty)))))
          ns  <- forms.notifiedSince(since)
          _   <- ns.traverse_((u, _) => publish(FeedItem.Notified(u)))
        yield (evs.lastOption.fold(last)(_._1), (since :: ns.map(_._2)).max)
      def go(last: Long, since: Instant): IO[Nothing] =
        IO.sleep(500.millis) *> step(last, since).handleErrorWith(e => smohua.observe.Log.println(s"[smohua] event feed failed: $e").as((last, since)))
          .flatMap((l, s) => go(l, s))
      go(start, now)
    }

  /** 購読する。接続が多すぎれば None。 */
  def subscribe(user: UUID): IO[Option[Stream[IO, FeedItem]]] =
    perUser.modify { m =>
      val n = m.getOrElse(user, 0)
      if n >= MaxPerUser then (m, false) else (m.updated(user, n + 1), true)
    }.flatMap {
      case false => IO.pure(None)
      case true =>
        val release = perUser.update(m => m.get(user).fold(m)(n => if n <= 1 then m - user else m.updated(user, n - 1)))
        Some(Stream.bracket(
          (Queue.dropping[IO, FeedItem](1024), ids.getAndUpdate(_ + 1)).tupled.flatTap((q, id) => subs.update(_ + (id -> q))))(
          (_, id) => subs.update(_ - id) *> release
        ).flatMap((q, _) => Stream.fromQueueUnterminated(q))).pure[IO]
    }

object EventFeed:
  def apply(forms: FormStore): IO[EventFeed] =
    (Ref.of[IO, Map[Long, Queue[IO, FeedItem]]](Map.empty), Ref.of[IO, Map[UUID, Int]](Map.empty), Ref.of[IO, Long](0L))
      .mapN(new EventFeed(forms, _, _, _))

final class StreamRoutes(feed: EventFeed, val sessions: SessionAuth) extends RouteSupport:

  private def sse(kind: String, data: Json): ServerSentEvent = ServerSentEvent(data = Some(data.noSpaces), eventType = Some(kind))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    /**
     * 画面への知らせ。自分のフォーム（持ち主・編集できるもの）に変更や回答があれば change（{form, kind}）、
     * 自分への通知があれば notify を送る。届いたら画面が読み直す。
     */
    case req @ GET -> Root / "api" / "stream" =>
      authed(req) { s =>
        feed.subscribe(s.user.id).flatMap {
          case None => err(Status.TooManyRequests, "同時に開いている画面が多すぎます")
          case Some(feedItems) =>
            val events = feedItems.collect {
              case FeedItem.Changed(f, id, kind, ws) if ws.contains(s.user.id) => sse("change", Json.obj("latest" -> id.asJson, "form" -> f.asJson, "kind" -> kind.asJson))
              case FeedItem.Notified(u) if u == s.user.id => sse("notify", Json.obj())
            }
            // 接続が切られないよう 20 秒ごとに空の行を送る
            val ping = Stream.awakeEvery[IO](20.seconds).as(ServerSentEvent(comment = Some("ping")))
            // ログアウト・ユーザーの無効化・キーの失効の後は流し続けない（1分ごとに確かめて切る）
            val revoked = Stream.awakeEvery[IO](60.seconds).evalMap(_ => sessions.alive(req).map(!_).handleError(_ => false))
            val body = (Stream.emit(ServerSentEvent(retry = Some(5.seconds), comment = Some("hello"))) ++ events.merge(ping)).interruptWhen(revoked)
            Ok(body.through(st => Stream.bracket(smohua.observe.Metrics.upDown("smohua.sse.connections", 1))(_ => smohua.observe.Metrics.upDown("smohua.sse.connections", -1)) >> st))
              .map(_.putHeaders("Cache-Control" -> "no-cache", "X-Accel-Buffering" -> "no"))
        }
      }
  }
