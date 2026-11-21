package smohua.core

import io.circe.Json
import io.circe.syntax.*
import smohua.shared.*

/** フォームの規則（質問の確認・回答の確認・集計・CSV）。DB も HTTP も使わない。 */
object Forms {

  val MaxQuestions = 100
  val MaxOptions = 100
  val MaxLabel = 300
  val MaxShort = 500
  val MaxLong = 10000
  private val IdRe = "^[A-Za-z0-9_-]{1,40}$".r
  private val DateRe = "^\\d{4}-\\d{2}-\\d{2}$".r

  /** メールアドレスの形（ローカル部@ドメイン。空白・制御文字・山括弧・カンマは入れない。ヘッダーに差し込まれないように）。 */
  private val EmailRe =
    "^[^\\s@<>,;\"\\\\]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)+$".r

  /** メールアドレスとして送れる形か（254文字まで）。 */
  def isEmail(s: String): Boolean = s.length <= 254 && EmailRe.matches(s) && !s.exists(_.isControl)

  /** 題名（改行などの制御文字は空白にする。メールの件名・CSV の見出し・通知の1行目にそのまま入るため）。 */
  def title(s: String): Either[String, String] = {
    val t = s.map(c => if c.isControl then ' ' else c).trim
    if t.isEmpty then Left("フォームの題名を入力してください")
    else if t.length > 200 then Left("フォームの題名は200文字までにしてください")
    else Right(t)
  }

  /** 質問を整える（前後の空白・空の選択肢・重複した選択肢を除く）。だめなら理由。 */
  def normalize(qs: List[QuestionDto]): Either[String, List[QuestionDto]] =
    if qs.size > MaxQuestions then Left(s"質問は${MaxQuestions}個までです")
    else if qs.map(_.id).distinct.size != qs.size then Left("質問の id が重複しています")
    else
      qs.zipWithIndex.foldRight[Either[String, List[QuestionDto]]](Right(Nil)) { case ((q, i), acc) =>
        for rest <- acc; one <- question(q, i + 1) yield one :: rest
      }

  private def question(q: QuestionDto, n: Int): Either[String, QuestionDto] = {
    val label = q.label.trim
    val opts = q.options.map(_.trim).filter(_.nonEmpty).distinct
    if !IdRe.matches(q.id) then Left(s"質問$n: id は半角の英数字・_・- の40文字までにしてください")
    else if !QuestionKind.all.contains(q.kind) then Left(s"質問$n: 種類（kind）は ${QuestionKind.all.mkString("・")} のどれかです")
    else if label.isEmpty then Left(s"質問$n: 質問文を入力してください")
    else if label.length > MaxLabel then Left(s"質問$n: 質問文は${MaxLabel}文字までにしてください")
    else if QuestionKind.hasOptions(q.kind) && opts.isEmpty then Left(s"質問$n「$label」: 選択肢を1つ以上入力してください")
    else if opts.size > MaxOptions || opts.exists(_.length > MaxLabel) then
      Left(s"質問$n「$label」: 選択肢は${MaxOptions}個まで、それぞれ${MaxLabel}文字までです")
    else if q.kind == QuestionKind.Scale &&
      !(q.scaleMin >= 0 && q.scaleMin <= 1 && q.scaleMax > q.scaleMin && q.scaleMax <= 10)
    then
      Left(s"質問$n「$label」: 段階は 0 か 1 から、最大 10 までにしてください")
    else
      Right(q.copy(
        label = label,
        help = q.help.trim.take(1000),
        options = if QuestionKind.hasOptions(q.kind) then opts else Nil,
        scaleMin = if q.kind == QuestionKind.Scale then q.scaleMin else 1,
        scaleMax = if q.kind == QuestionKind.Scale then q.scaleMax else 5
      ))
  }

  /** 回答を確かめて整える（質問にない項目は捨てる。空の回答は入れない）。だめなら理由の一覧。 */
  def validate(qs: List[QuestionDto], answers: Map[String, Json]): Either[List[String], Map[String, Json]] = {
    val results = qs.map(q => answer(q, answers.get(q.id).filterNot(_.isNull)).map(_.map(q.id -> _)))
    val errors = results.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors) else Right(results.collect { case Right(Some(kv)) => kv }.toMap)
  }

  private def answer(q: QuestionDto, v: Option[Json]): Either[String, Option[Json]] = {
    def bad(m: String) = Left(s"「${q.label}」: $m")
    def text(max: Int): Either[String, Option[Json]] =
      v.fold[Either[String, Option[Json]]](Right(None))(_.asString.map(_.trim) match {
        case None => bad("文字で入力してください")
        case Some(s) if s.length > max => bad(s"${max}文字までにしてください")
        case Some(s) => Right(Option.when(s.nonEmpty)(s.asJson))
      })
    val checked: Either[String, Option[Json]] = q.kind match {
      case QuestionKind.Short => text(MaxShort)
      case QuestionKind.Long => text(MaxLong)
      case QuestionKind.Single | QuestionKind.Dropdown =>
        text(MaxLabel).flatMap {
          case Some(j) if !j.asString.exists(q.options.contains) => bad("選択肢から選んでください")
          case other => Right(other)
        }
      case QuestionKind.Multi =>
        v.fold[Either[String, Option[Json]]](Right(None))(_.as[List[String]].toOption match {
          case None => bad("選択肢の配列で指定してください")
          case Some(xs) if !xs.forall(q.options.contains) => bad("選択肢から選んでください")
          // 選択肢の並びにそろえる
          case Some(xs) => Right(Option.when(xs.nonEmpty)(q.options.filter(xs.contains).asJson))
        })
      case QuestionKind.Scale =>
        v.fold[Either[String, Option[Json]]](Right(None))(j =>
          j.asNumber.flatMap(_.toInt).orElse(j.asString.flatMap(_.trim.toIntOption)) match {
            case Some(n) if n >= q.scaleMin && n <= q.scaleMax => Right(Some(n.asJson))
            case _ if j.asString.exists(_.trim.isEmpty) => Right(None)
            case _ => bad(s"${q.scaleMin}〜${q.scaleMax} で選んでください")
          }
        )
      case QuestionKind.Number =>
        v.fold[Either[String, Option[Json]]](Right(None))(j =>
          j.asNumber.map(_.toDouble).orElse(j.asString.flatMap(_.trim.toDoubleOption)) match {
            case Some(d) if !d.isNaN && !d.isInfinite =>
              Right(Some(if d == d.toLong.toDouble then d.toLong.asJson else d.asJson))
            case _ if j.asString.exists(_.trim.isEmpty) => Right(None)
            case _ => bad("数で入力してください")
          }
        )
      case QuestionKind.Date =>
        text(10).flatMap {
          case Some(j)
              if !j.asString.exists(s => DateRe.matches(s) && scala.util.Try(java.time.LocalDate.parse(s)).isSuccess) =>
            bad("日付（yyyy-MM-dd）で入力してください")
          case other => Right(other)
        }
      case QuestionKind.Email =>
        text(254).flatMap {
          case Some(j) if !j.asString.exists(isEmail) => bad("メールアドレスの形で入力してください")
          case other => Right(other)
        }
      case _ => Right(None)
    }
    checked.flatMap {
      case None if q.required => bad("入力してください")
      case other => Right(other)
    }
  }

  /** 質問ごとの集計。選択肢・段階は件数（0 件も並べる）、段階・数は平均、記述は新しいものから textLimit 件。answers は新しい順に渡す。 */
  def summarize(qs: List[QuestionDto],
                answers: List[Map[String, Json]],
                textLimit: Int = 20): List[QuestionSummaryDto] =
    qs.map { q =>
      val vs = answers.flatMap(_.get(q.id))
      val picked: List[String] =
        vs.flatMap(j => j.asArray.map(_.toList.flatMap(_.asString)).getOrElse(List(Answers.text(j))))
      val labels =
        if QuestionKind.hasOptions(q.kind) then
          q.options ++ picked.distinct.filterNot(q.options.contains) // 消した選択肢への回答も出す
        else if q.kind == QuestionKind.Scale then (q.scaleMin to q.scaleMax).toList.map(_.toString)
        else Nil
      val by = picked.groupMapReduce(identity)(_ => 1)(_ + _)
      val nums = vs.flatMap(_.asNumber.map(_.toDouble))
      QuestionSummaryDto(
        q.id,
        q.kind,
        q.label,
        vs.size,
        labels.map(l => CountDto(l, by.getOrElse(l, 0))),
        Option.when(
          (q.kind == QuestionKind.Scale || q.kind == QuestionKind.Number) && nums.nonEmpty
        )(math.round(nums.sum / nums.size * 100) / 100.0),
        if QuestionKind.counted(q.kind) then Nil else vs.map(Answers.text).filter(_.nonEmpty).take(textLimit)
      )
    }

  val MaxCss = 20000
  val MaxSubmitLabel = 30
  private val ColorRe = "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$".r

  /**
   * 見た目の設定を確かめて整える。CSS は回答の画面に足すだけなので HTML としては入らないが、外のサイトを読み込むもの
   * （@import・http(s): や // の参照）は、回答する人の IP が外に漏れるため受け付けない。
   */
  def appearance(a: FormAppearanceDto): Either[String, FormAppearanceDto] = {
    val colors =
      List("背景色" -> a.background, "カードの色" -> a.card, "文字色" -> a.text, "強調色" -> a.accent).map((n, v) => n -> v.trim)
    val css = a.css.replace("\r\n", "\n").trim
    // ブラウザが URL を読むときと同じに見る: \ は / と同じ（"/\host" は "//host"）、制御文字と空白は URL の頭や途中から除かれる
    val flat = unescapeCss(css).toLowerCase.replace('\\', '/').replaceAll("[\\x00-\\x20]+", "")
    // コメントを除いた形と除かない形の両方で確かめる（文字列の中の /* … */ はコメントではないので、除くだけだとその間に隠せる）
    val views = List(flat, flat.replaceAll("/\\*.*?\\*/", ""))
    def has(s: String): Boolean = views.exists(_.contains(s))
    colors.collectFirst { case (n, v) if v.nonEmpty && !ColorRe.matches(v) => s"${n}は #rgb か #rrggbb の形で入力してください" }
      .toLeft(())
      .flatMap(_ =>
        Either.cond(a.submitLabel.trim.length <= MaxSubmitLabel, (), s"送信ボタンの文言は${MaxSubmitLabel}文字までにしてください")
      )
      .flatMap(_ => Either.cond(css.length <= MaxCss, (), s"CSS は${MaxCss}文字までにしてください"))
      .flatMap(_ => Either.cond(!has("@import"), (), "CSS に @import は使えません"))
      // url() のほか image-set() の文字列からも読み込めるので、http(s): と // で始まる参照をまとめて断る
      .flatMap(_ =>
        Either.cond(
          !has("http:") && !has("https:") && !views.exists(v => "(url\\(|[\"'])//".r.findFirstIn(v).isDefined),
          (),
          "CSS で外のサイトは読み込めません（url() は data: だけ使えます）"
        )
      )
      .flatMap(_ => Either.cond(!css.contains("</") && !has("</"), (), "CSS に </ は使えません"))
      .map { _ =>
        val c = colors.map(_._2.toLowerCase)
        a.copy(background = c(0), card = c(1), text = c(2), accent = c(3), submitLabel = a.submitLabel.trim, css = css)
      }
  }

  /**
   * CSS のエスケープ（\\75 や \\u など）を戻す（エスケープで @import・url() の確認をすり抜けられないように）。
   * \\ の直後の改行は、文字列の中では「行の続き」で何も残らない（"ht\\<改行>tp:" は "http:"）。
   */
  private[core] def unescapeCss(css: String): String =
    "(?s)\\\\(?:([0-9a-fA-F]{1,6})\\s?|(.))".r.replaceAllIn(
      css,
      m =>
        java.util.regex.Matcher.quoteReplacement(Option(m.group(1)).fold(
          Option(m.group(2)).filterNot(c => c == "\n" || c == "\r" || c == "\f").getOrElse("")
        )(h => scala.util.Try(new String(Character.toChars(Integer.parseInt(h, 16)))).getOrElse("")))
    )

  /** 受付期間（始まりは終わりより前）。 */
  def schedule(s: FormScheduleDto): Either[String, FormScheduleDto] =
    (s.from, s.until) match {
      case (Some(f), Some(u)) if !f.isBefore(u) => Left("受付の終わりは、始まりより後にしてください")
      case _ => Right(s)
    }

  /** CSV の1セル。表計算ソフトで式として動かないよう、= + - @ で始まる値には ' を付ける。 */
  def csvCell(s: String): String = {
    val v = if s.nonEmpty && "=+-@\t\r".contains(s.head) then "'" + s else s
    if v.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then "\"" + v.replace("\"", "\"\"") + "\"" else v
  }

  def csvLine(cells: List[String]): String = cells.map(csvCell).mkString(",") + "\r\n"

  /** 回答の一覧の見出し。 */
  def csvHeader(qs: List[QuestionDto]): List[String] = List("番号", "回答日時", "回答者") ++ qs.map(_.label)

  /** 通知に載せる回答の要約（最初のいくつかの質問と回答）。 */
  def digest(qs: List[QuestionDto], answers: Map[String, Json], max: Int = 5): String =
    digestLines(qs, answers, max).map((l, a) => s"$l: $a").mkString("\n")

  /** 要約の行（質問と回答の組）。 */
  def digestLines(qs: List[QuestionDto], answers: Map[String, Json], max: Int = 5): List[(String, String)] =
    qs.flatMap(q =>
      Option(Answers.of(answers, q)).filter(_.nonEmpty).map(a => q.label -> a.replaceAll("\\s+", " ").take(120))
    ).take(max)

  /** 回答の控えの宛先: 「メールアドレス」の質問のうち、最初に答えのあるもの。 */
  def receiptAddress(qs: List[QuestionDto], answers: Map[String, Json]): Option[String] =
    qs.filter(_.kind == QuestionKind.Email).flatMap(q => answers.get(q.id).flatMap(_.asString)).find(isEmail)
}
