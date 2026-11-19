package smohua.web

import smohua.shared.*

import scala.scalajs.js

/** 表示の補助. */
object Fmt {

  private def opts(o: (String, String)*): js.Dynamic = js.Dynamic.literal(o.map((k, v) => k -> (v: js.Any))*)

  def dateTime(t: Timestamp): String =
    t.toDate.asInstanceOf[js.Dynamic].toLocaleString(
      "ja-JP",
      opts(
        "year" -> "numeric",
        "month" -> "2-digit",
        "day" -> "2-digit",
        "hour" -> "2-digit",
        "minute" -> "2-digit"
      )
    ).asInstanceOf[String]

  /** 「3分前」「昨日」など. */
  def ago(t: Timestamp): String = {
    val diff = (js.Date.now() - t.toEpochMilli) / 1000
    if (diff < 60) "たった今"
    else if (diff < 3600) s"${(diff / 60).toInt}分前"
    else if (diff < 86400) s"${(diff / 3600).toInt}時間前"
    else if (diff < 86400 * 7) s"${(diff / 86400).toInt}日前"
    else dateTime(t).take(10)
  }

  def initials(name: String): String = name.trim.take(1)

  private def pad(n: Int): String = if (n < 10) s"0$n" else n.toString

  /** 日時の入力欄（datetime-local）に入れる、手元の時刻の "yyyy-MM-ddTHH:mm". */
  def localInput(t: Timestamp): String = {
    val d = t.toDate
    s"${d.getFullYear().toInt}-${pad(d.getMonth().toInt + 1)}-${pad(d.getDate().toInt)}T${pad(d.getHours().toInt)}:${pad(d.getMinutes().toInt)}"
  }

  /** 日時の入力欄の値（手元の時刻）を日時に. 空や読めない値は None. */
  def fromLocalInput(v: String): Option[Timestamp] =
    Option(v.trim).filter(_.nonEmpty).map(new js.Date(_)).filterNot(_.getTime().isNaN).map(d =>
      Timestamp(d.toISOString())
    )

}
