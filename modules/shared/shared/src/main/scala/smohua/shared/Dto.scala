package smohua.shared

import io.circe.*

import java.util.UUID

// バックエンド(JVM)と画面(Scala.js)の両方で使う API の型。
// 日時は Timestamp（JVM では Instant）、日付は "yyyy-MM-dd" の文字列（Scala.js で java.time を使わないため）。

final case class ErrorDto(error: String) derives Encoder.AsObject, Decoder

object ApiScope {
  val Read = "read"
  val Write = "write"
  val Admin = "admin"
  val all: List[String] = List(Read, Write, Admin)
}

// ------------------------------------------------------------------ ユーザー

final case class UserBrief(id: UUID, name: String, avatarUrl: Option[String], bot: Boolean = false) derives Encoder.AsObject, Decoder

final case class UserDto(id: UUID, name: String, email: Option[String], avatarUrl: Option[String], isAdmin: Boolean, disabled: Boolean,
    lastLoginAt: Option[Timestamp], groups: List[String]) derives Encoder.AsObject, Decoder

final case class MeDto(id: UUID, name: String, email: Option[String], avatarUrl: Option[String], isAdmin: Boolean, timezone: String,
    via: String, scopes: List[String]) derives Encoder.AsObject, Decoder

final case class UpdateUserReq(isAdmin: Option[Boolean], disabled: Option[Boolean]) derives Encoder.AsObject, Decoder

final case class ApiKeyDto(id: UUID, name: String, prefix: String, scopes: List[String], createdAt: Timestamp, expiresAt: Option[Timestamp],
    lastUsedAt: Option[Timestamp], client: Option[String]) derives Encoder.AsObject, Decoder

final case class CreateApiKeyReq(name: String, scopes: List[String], expiresInDays: Option[Int]) derives Encoder.AsObject, Decoder

final case class CreatedApiKeyDto(key: String, info: ApiKeyDto) derives Encoder.AsObject, Decoder

final case class AuditDto(id: Long, at: Timestamp, actor: Option[UserBrief], via: String, action: String, target: Option[String],
    detail: Json) derives Encoder.AsObject, Decoder

/** 画面の初期設定. mail はメール（SMTP）で通知を送れるか、stui は stui との連携が設定されているか. */
final case class ClientConfigDto(devLogin: Boolean, version: String, mail: Boolean, stui: Boolean) derives Encoder.AsObject, Decoder

/** 連携できるサービスと、自分が連携済みかどうか. */
final case class LinkServiceDto(provider: String, name: String, url: String, connected: Boolean) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ 通知（画面内）

final case class NotificationDto(id: UUID, kind: String, formId: Option[UUID], title: String, body: String, link: String, at: Timestamp,
    read: Boolean) derives Encoder.AsObject, Decoder

final case class NotificationListDto(items: List[NotificationDto], unread: Int) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ フォーム

/** フォームの状態. draft（下書き. 回答できない）・open（受付中）・closed（締め切り）. */
object FormStatus {
  val Draft = "draft"
  val Open = "open"
  val Closed = "closed"
  val all: List[String] = List(Draft, Open, Closed)
  def label(s: String): String = s match {
    case Draft  => "下書き"
    case Open   => "受付中"
    case Closed => "締め切り"
    case _      => s
  }
}

/** だれが回答できるか. anyone（URL を知っている人. ログイン不要）・login（ログインした人）. */
object FormAccess {
  val Anyone = "anyone"
  val Login = "login"
  val all: List[String] = List(Anyone, Login)
  def label(s: String): String = s match {
    case Anyone => "URL を知っている人（ログイン不要）"
    case Login  => "ログインした人だけ"
    case _      => s
  }
}

/** 質問の種類. */
object QuestionKind {
  val Short = "short"       // 1行の文字
  val Long = "long"         // 複数行の文字
  val Single = "single"     // 1つ選ぶ（ラジオボタン）
  val Multi = "multi"       // いくつでも選ぶ（チェックボックス）
  val Dropdown = "dropdown" // 1つ選ぶ（プルダウン）
  val Scale = "scale"       // 段階（1〜5 など）
  val Date = "date"         // 日付
  val Number = "number"     // 数
  val all: List[String] = List(Short, Long, Single, Multi, Dropdown, Scale, Date, Number)
  /** 選択肢から選ぶ種類か. */
  def hasOptions(k: String): Boolean = k == Single || k == Multi || k == Dropdown
  /** 集計で件数を数える種類か（選択肢・段階）. */
  def counted(k: String): Boolean = hasOptions(k) || k == Scale
  def label(k: String): String = k match {
    case Short    => "記述（1行）"
    case Long     => "記述（複数行）"
    case Single   => "1つ選ぶ"
    case Multi    => "複数選ぶ"
    case Dropdown => "プルダウン"
    case Scale    => "段階で選ぶ"
    case Date     => "日付"
    case Number   => "数"
    case _        => k
  }
}

/** 質問. id はフォームの中で一意（回答はこの id に結び付く. 文言を変えても回答は残る）. scaleMin・scaleMax は段階の両端. */
final case class QuestionDto(id: String, kind: String, label: String, help: String = "", required: Boolean = false,
    options: List[String] = Nil, scaleMin: Int = 1, scaleMax: Int = 5) derives Encoder.AsObject

object QuestionDto {
  /** 省略できる項目に既定値を使う（derives の Decoder は既定値を使わないため）. */
  given Decoder[QuestionDto] = Decoder.instance { c =>
    for {
      id    <- c.get[String]("id")
      kind  <- c.get[String]("kind")
      label <- c.get[String]("label")
    } yield QuestionDto(id, kind, label, c.get[String]("help").getOrElse(""), c.get[Boolean]("required").getOrElse(false),
      c.get[List[String]]("options").getOrElse(Nil), c.get[Int]("scaleMin").getOrElse(1), c.get[Int]("scaleMax").getOrElse(5))
  }
}

/**
 * 回答が届いたときの知らせ方（フォームの持ち主と編集できる人へ）. inApp は画面内とプッシュ通知、mail はメール（SMTP. skereru など）、
 * stui は stui（本人が連携しているとき. stuiChannel があればそのチャンネルへ、なければボットからの DM）.
 */
final case class FormNotifyDto(inApp: Boolean = true, mail: Boolean = false, stui: Boolean = false, stuiChannel: String = "")
    derives Encoder.AsObject

object FormNotifyDto {
  /** 省略した項目は既定値（保存してある古い形も読める）. */
  given Decoder[FormNotifyDto] = Decoder.instance(c => Right(FormNotifyDto(c.get[Boolean]("inApp").getOrElse(true),
    c.get[Boolean]("mail").getOrElse(false), c.get[Boolean]("stui").getOrElse(false), c.get[String]("stuiChannel").getOrElse(""))))
}

/** 受付期間. from より前は「受付前」、until 以降は「受付終了」（状態が「受付中」のときだけ効く）. どちらも省略できる. */
final case class FormScheduleDto(from: Option[Timestamp] = None, until: Option[Timestamp] = None) derives Encoder.AsObject, Decoder

/** 回答する人から見た受付の段階. draft・before（受付前）・open・after（受付期間が終わった）・closed. */
object FormPhase {
  val Draft = "draft"
  val Before = "before"
  val Open = "open"
  val After = "after"
  val Closed = "closed"
}

/**
 * 回答の画面の見た目（iframe で埋め込むとき向け）. 色は #rgb か #rrggbb（空なら既定の色）. submitLabel は送信ボタンの文言（空なら「送信」）.
 * showTitle は題名、showDescription は説明、showBranding は「smohua のフォーム」、showAnonymous は「この回答は匿名で送られます」を出すか.
 * css は回答の画面にだけ足す CSS（高度な設定）.
 */
final case class FormAppearanceDto(background: String = "", card: String = "", text: String = "", accent: String = "", submitLabel: String = "",
    showTitle: Boolean = true, showDescription: Boolean = true, showBranding: Boolean = true, showAnonymous: Boolean = true, css: String = "")
    derives Encoder.AsObject

object FormAppearanceDto {
  /** 省略した項目は既定値. */
  given Decoder[FormAppearanceDto] = Decoder.instance { c =>
    def s(k: String) = c.get[String](k).getOrElse("")
    def b(k: String) = c.get[Boolean](k).getOrElse(true)
    Right(FormAppearanceDto(s("background"), s("card"), s("text"), s("accent"), s("submitLabel"), b("showTitle"), b("showDescription"),
      b("showBranding"), b("showAnonymous"), s("css")))
  }
}

final case class FormDto(id: UUID, title: String, description: String, status: String, access: String, oneResponse: Boolean,
    confirmation: String, questions: List[QuestionDto], notice: FormNotifyDto, schedule: FormScheduleDto, appearance: FormAppearanceDto,
    owner: UserBrief, editors: List[UserBrief], responseCount: Int, lastResponseAt: Option[Timestamp], createdAt: Timestamp, updatedAt: Timestamp, canEdit: Boolean)
    derives Encoder.AsObject, Decoder

/** 一覧の1行. */
final case class FormSummaryDto(id: UUID, title: String, status: String, access: String, questionCount: Int, responseCount: Int,
    lastResponseAt: Option[Timestamp], owner: UserBrief, updatedAt: Timestamp) derives Encoder.AsObject, Decoder

final case class CreateFormReq(title: String, description: Option[String] = None, questions: Option[List[QuestionDto]] = None)
    derives Encoder.AsObject, Decoder

/** 変える項目だけを指定する. */
final case class UpdateFormReq(title: Option[String] = None, description: Option[String] = None, status: Option[String] = None,
    access: Option[String] = None, oneResponse: Option[Boolean] = None, confirmation: Option[String] = None,
    questions: Option[List[QuestionDto]] = None, notice: Option[FormNotifyDto] = None, editorIds: Option[List[UUID]] = None,
    schedule: Option[FormScheduleDto] = None, appearance: Option[FormAppearanceDto] = None)
    derives Encoder.AsObject, Decoder

/** 回答する人に見せるフォーム（持ち主・通知の設定などは出さない）. answered は「1人1回」のフォームに回答済みか. phase は FormPhase. */
final case class PublicFormDto(id: UUID, title: String, description: String, status: String, access: String, oneResponse: Boolean,
    questions: List[QuestionDto], answered: Boolean, needLogin: Boolean, phase: String, schedule: FormScheduleDto, appearance: FormAppearanceDto) derives Encoder.AsObject, Decoder

/** 回答. answers は 質問の id → 値（文字・数は文字列、複数選ぶ質問は文字列の配列）. */
final case class SubmitReq(answers: Map[String, Json]) derives Encoder.AsObject, Decoder

final case class SubmittedDto(id: UUID, confirmation: String) derives Encoder.AsObject, Decoder

final case class ResponseDto(id: UUID, seq: Long, respondent: Option[UserBrief], answers: Map[String, Json], createdAt: Timestamp)
    derives Encoder.AsObject, Decoder

final case class ResponsePageDto(total: Int, responses: List[ResponseDto]) derives Encoder.AsObject, Decoder

/** 選択肢ごとの件数. */
final case class CountDto(label: String, count: Int) derives Encoder.AsObject, Decoder

/** 質問ごとの集計. counts は選択肢・段階の件数、average は段階・数の平均、texts は記述の新しいもの. */
final case class QuestionSummaryDto(id: String, kind: String, label: String, answered: Int, counts: List[CountDto], average: Option[Double],
    texts: List[String]) derives Encoder.AsObject, Decoder

final case class FormSummaryStatsDto(formId: UUID, title: String, status: String, total: Int, lastResponseAt: Option[Timestamp],
    questions: List[QuestionSummaryDto]) derives Encoder.AsObject, Decoder

/** 回答の値の表示（一覧・CSV・通知で同じ形にする）. */
object Answers {
  def text(j: Json): String =
    j.asString.orElse(j.asNumber.map(n => n.toLong.map(_.toString).getOrElse(n.toDouble.toString)))
      .orElse(j.asArray.map(_.flatMap(_.asString).mkString("、"))).orElse(j.asBoolean.map(b => if (b) "はい" else "いいえ")).getOrElse("")

  def of(answers: Map[String, Json], q: QuestionDto): String = answers.get(q.id).map(text).getOrElse("")
}

/** 画面の URL（サーバーの通知と画面で同じ形を使う）. */
object Paths {
  def form(id: UUID): String = s"/forms/$id"
  def responses(id: UUID): String = s"/forms/$id/responses"
  /** 回答する人に渡す URL. */
  def fill(id: UUID): String = s"/f/$id"
  /** 同じサイトの中のパスか（ログイン・連携の後に戻る先. "//…"・"/\…" は別のサイトになるので除く）. サーバーと画面で同じ確かめ方をする. */
  def isLocal(p: String): Boolean = p.startsWith("/") && !p.startsWith("//") && !p.startsWith("/\\") && !p.exists(_.isControl)
}

object Html {
  def escape(s: String): String = {
    val b = new StringBuilder(s.length + 16)
    s.foreach {
      case '<'  => b.append("&lt;")
      case '>'  => b.append("&gt;")
      case '&'  => b.append("&amp;")
      case '"'  => b.append("&quot;")
      case '\'' => b.append("&#39;")
      case c    => b.append(c)
    }
    b.toString
  }
}
