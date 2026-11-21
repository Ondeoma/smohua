package smohua.api

import io.circe.Json
import io.circe.syntax.*
import sbirds.links.StuiMessage
import smohua.core.Forms
import smohua.db.{FormRow, ResponseRow}
import smohua.shared.*

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

/**
 * 通知先に送る本文. Webhook には JSON をそのまま送り（X-Smohua-Event にイベントの名前）、チャットの受信 Webhook とメールには、そこから作った文を送る.
 *  - response.created: {"event", "form": {"id", "title", "url", "responsesUrl"}, "response": {"id", "seq", "createdAt", "respondent": {"name", "email"} | null,
 *    "url", "answers": [{"questionId", "label", "kind", "value", "text"}]}}
 *  - form.opened・form.closed: {"event", "form", "at", "responseCount"}
 *  - test: {"event", "form", "message"}
 */
object FormPayload {

  val Test = "test"

  private def form(f: FormRow, publicUrl: String): Json =
    Json.obj(
      "id" -> f.id.asJson,
      "title" -> f.title.asJson,
      "url" -> s"$publicUrl${Paths.fill(f.id)}".asJson,
      "responsesUrl" -> s"$publicUrl${Paths.responses(f.id)}".asJson
    )

  /** 回答が届いた. who は回答した人（ログインして回答したときだけ. 名前とメールアドレス）. */
  def response(f: FormRow, r: ResponseRow, who: Option[(String, Option[String])], publicUrl: String): Json = {
    val answers = r.answers.as[Map[String, Json]].getOrElse(Map.empty)
    Json.obj(
      "event" -> FormEvent.ResponseCreated.asJson,
      "form" -> form(f, publicUrl),
      "response" -> Json.obj(
        "id" -> r.id.asJson,
        "seq" -> r.seq.asJson,
        "createdAt" -> r.createdAt.toString.asJson,
        "respondent" -> who.map((n, e) => Json.obj("name" -> n.asJson, "email" -> e.asJson)).asJson,
        "url" -> s"$publicUrl${Paths.responses(f.id)}".asJson,
        "answers" -> f.questions.map(q =>
          Json.obj(
            "questionId" -> q.id.asJson,
            "label" -> q.label.asJson,
            "kind" -> q.kind.asJson,
            "value" -> answers.getOrElse(q.id, Json.Null),
            "text" -> Answers.of(answers, q).asJson
          )
        ).asJson
      )
    )
  }

  /** 受付を始めた（accepting = true）・締め切った. */
  def status(f: FormRow, accepting: Boolean, responseCount: Int, at: Instant, publicUrl: String): Json =
    Json.obj(
      "event" -> (if accepting then FormEvent.Opened else FormEvent.Closed).asJson,
      "form" -> form(f, publicUrl),
      "at" -> at.toString.asJson,
      "responseCount" -> responseCount.asJson
    )

  def test(f: FormRow, userName: String, publicUrl: String): Json =
    Json.obj(
      "event" -> Test.asJson,
      "form" -> form(f, publicUrl),
      "message" -> s"$userName さんが送った、試しの通知です".asJson
    )

  def event(p: Json): String = p.hcursor.get[String]("event").getOrElse(FormEvent.ResponseCreated)

  /** 見出しと本文（文のまま. チャットに送るときは plain で記号を無害にする）と、開く URL. */
  final case class Text(title: String, lines: List[String], url: String)

  def text(p: Json, zone: ZoneId): Text = {
    val h = p.hcursor
    val title = h.downField("form").get[String]("title").getOrElse("")
    val formUrl = h.downField("form").get[String]("url").getOrElse("")
    val responsesUrl = h.downField("form").get[String]("responsesUrl").getOrElse(formUrl)
    event(p) match {
      case FormEvent.ResponseCreated =>
        val r = h.downField("response")
        val who = r.downField("respondent").get[String]("name").toOption.map(n => s"回答者: $n")
        val answers = r.get[List[Json]]("answers").getOrElse(Nil).flatMap { a =>
          val c = a.hcursor
          c.get[String]("text").toOption.filter(_.nonEmpty).map(t =>
            s"${c.get[String]("label").getOrElse("")}: ${t.replaceAll("\\s+", " ").take(120)}"
          )
        }
        Text(s"「$title」に回答が届きました", who.toList ++ answers.take(5), responsesUrl)
      case FormEvent.Opened => Text(s"「$title」の受付を始めました", List(s"回答の URL: $formUrl"), formUrl)
      case FormEvent.Closed =>
        val n = h.get[Int]("responseCount").getOrElse(0)
        val at = h.get[String]("at").toOption.flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
        Text(
          s"「$title」を締め切りました",
          at.map(i => s"締め切り: ${when(i, zone)}").toList :+ s"回答: ${n}件",
          responsesUrl
        )
      case _ => Text(s"「$title」の通知の試し", List(h.get[String]("message").getOrElse("")), formUrl)
    }
  }

  private val Fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  def when(i: Instant, zone: ZoneId): String = Fmt.format(i.atZone(zone))

  /** チャット（stui・Slack・Mattermost）に投稿する文. 回答は外の人が書けるので、Markdown の記号は無害にする. */
  def chat(p: Json, zone: ZoneId): StuiMessage = {
    val t = text(p, zone)
    StuiMessage(
      plain(t.title).take(300),
      t.lines.map(plain).mkString("\n").take(sbirds.links.StuiClient.MaxText),
      t.url
    )
  }

  /** メールの件名と本文. */
  def mail(p: Json, zone: ZoneId, appName: String): (String, String) = {
    val t = text(p, zone)
    (
      s"[$appName] ${t.title}",
      (List(t.title, "") ++ t.lines ++ List("", t.url, "", "-- ", s"$appName（通知はフォームの「設定」で変えられます）")).mkString("\n")
    )
  }

  /** 回答の控え（回答した人へのメール）. すべての質問と答えを載せる. */
  def receipt(f: FormRow, answers: Map[String, Json], appName: String): (String, String) = {
    val lines =
      f.questions.flatMap(q => Option(Answers.of(answers, q)).filter(_.nonEmpty).map(a => s"■ ${q.label}\n$a\n"))
    (
      s"[$appName] 「${f.title}」への回答の控え",
      (List(s"「${f.title}」への回答を受け付けました。", "") ++
        Option(f.confirmation.trim).filter(_.nonEmpty).toList.flatMap(c => List(c, "")) ++
        List("回答の内容:", "") ++ lines ++
        List("-- ", s"$appName から自動で送っています。心当たりがないときは、このメールを破棄してください。")).mkString("\n")
    )
  }

  /**
   * Markdown として解釈される記号をエスケープする（リンクや書式を作らせない）. 改行は空白にする.
   * stui の /api/apps/notify では title を stui がエスケープするので、本文だけに使う. 受信 Webhook には title にも使う.
   */
  def plain(s: String): String =
    s.replaceAll("[\\r\\n]+", " ").replaceAll("([\\\\`*_\\[\\]()<>#!|~])", "\\\\$1")

  /** 回答の要約（Forms.digest と同じ並び. 画面内の通知・stui の DM に使う）. */
  def digest(f: FormRow, r: ResponseRow): String =
    Forms.digest(f.questions, r.answers.as[Map[String, Json]].getOrElse(Map.empty))
}
