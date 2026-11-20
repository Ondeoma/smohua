package smohua.web

import smohua.shared.*

import scala.scalajs.js

/** 表示の補助. */
object Fmt {

  // 日時の表示（2026/11/15 09:30・「3分前」）は sbirds-lib
  export sbirds.web.Dates.{ago, dateTime}

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
