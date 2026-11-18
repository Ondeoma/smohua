package smohua.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import smohua.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

final case class Toast(id: Int, text: String, link: Option[String], error: Boolean)

/** サーバーから届いた変更の知らせ（番号は届くたびに増える. 各画面はこれを監視して読み直す）. */
final case class Change(seq: Int, formId: Option[UUID], kind: String)

/**
 * 画面全体で共有する状態と、変更の自動同期.
 * サーバーから SSE で知らせ（自分のフォームの変更・回答、自分への通知）を受けると、changes に流す（各画面が自分に関係のあるものを読み直す）.
 * SSE がつながらないとき・つなぎ直したときは、まとめて読み直す（kind = "sync"）.
 */
object Store {

  val me: RV[Option[MeDto]] = RV(None)
  val config: RV[Option[ClientConfigDto]] = RV(None)
  val unread: RV[Int] = RV(0)
  val toasts: RV[List[Toast]] = RV(Nil)
  val changes: RV[Change] = RV(Change(0, None, ""))

  private var toastSeq = 0
  private var changeSeq = 0

  def myId: Option[UUID] = me.v.map(_.id)

  /** システム管理者か. */
  def isAdmin = me.mapC(_.exists(_.isAdmin))

  // ---------------------------------------------------------------- トースト

  def toast(text: String, link: Option[String] = None, error: Boolean = false): Unit = {
    toastSeq += 1
    val t = Toast(toastSeq, text, link, error)
    toasts := (toasts.v :+ t).takeRight(5)
    dom.window.setTimeout(() => dismiss(t.id), if (error) 9000 else 6000)
  }

  def dismiss(id: Int): Unit = toasts := toasts.v.filterNot(_.id == id)

  def error(e: Throwable): Unit = e match {
    case ApiError(401, _) => ()
    case other            => toast(other.getMessage, error = true)
  }

  /** Future の失敗をトーストで知らせる. */
  def report[A](f: Future[A])(ok: A => Unit): Unit = f.onComplete {
    case Success(a) => ok(a)
    case Failure(e) => error(e)
  }

  // ---------------------------------------------------------------- 自動同期（SSE）

  private var stream: Option[dom.EventSource] = None
  private var lastSync = 0.0

  private def live: Boolean = stream.exists(_.readyState == dom.EventSource.OPEN)

  // まだ流していない知らせ（短い間に続けて届いたものは1つにまとめ、各画面の読み直しを1回にする）
  private var pending: Option[(Option[UUID], String)] = None

  private def emit(formId: Option[UUID], kind: String): Unit = pending match {
    case Some((f, k)) =>
      // 別のフォーム・別の種類が混ざったら、どのフォームの何かは決めない（各画面はすべて読み直す）
      pending = Some((if (f == formId) f else None, if (k == kind) k else "sync"))
    case None =>
      pending = Some((formId, kind))
      dom.window.setTimeout(() => flush(), 300)
  }

  // 画面が隠れている間は流さない（見えるようになったとき sync でまとめて読み直すので、隠れたまま何度も読み直さない）
  private def flush(): Unit = pending.foreach { (f, k) =>
    pending = None
    if (dom.document.visibilityState.toString == "visible") {
      changeSeq += 1
      changes := Change(changeSeq, f, k)
    }
  }

  /** 未読の通知の数を読み直す（増えていて、画面が隠れていればデスクトップにも出す）. */
  def refreshUnread(): Unit = if (me.v.isDefined) Api.unread().foreach { n =>
    if (n > unread.v) desktopNotify()
    if (n != unread.v) unread := n
  }

  /** まとめて読み直す（つなぎ直したとき・SSE がつながらないとき）. */
  def sync(): Unit = if (me.v.isDefined) {
    lastSync = js.Date.now()
    refreshUnread()
    emit(None, "sync")
  }

  def connect(): Unit = if (me.v.isDefined && stream.isEmpty) {
    val es = new dom.EventSource("/api/stream")
    es.addEventListener("change", (e: dom.MessageEvent) => {
      val d = js.JSON.parse(e.data.toString)
      emit(scala.util.Try(UUID.fromString(d.form.toString)).toOption, d.kind.toString)
    })
    es.addEventListener("notify", (_: dom.MessageEvent) => refreshUnread())
    // つなぎ直したときは取りこぼしを確かめる
    es.addEventListener("open", (_: dom.Event) => sync())
    stream = Some(es)
  }

  def startSync(): Unit = {
    // SSE でつながっている間は、念のため60秒ごとにだけ読み直す
    dom.window.setInterval(() => if (dom.document.visibilityState.toString == "visible") {
      // サーバーに断られて（429 など）ブラウザがつなぎ直すのをやめた SSE は、作り直す
      if (stream.exists(_.readyState == dom.EventSource.CLOSED)) { stream = None; connect() }
      if (!live || js.Date.now() - lastSync > 60000) sync()
    }, 10000)
    dom.document.addEventListener("visibilitychange", (_: dom.Event) => if (dom.document.visibilityState.toString == "visible") sync())
  }

  /** 画面が隠れているときに、新しい通知をデスクトップに出す（許可されていれば）. */
  private def desktopNotify(): Unit =
    if (dom.document.visibilityState.toString != "visible" && Prefs.get("desktop", "on") == "on" &&
        !js.isUndefined(dom.window.asInstanceOf[js.Dynamic].Notification) && dom.window.asInstanceOf[js.Dynamic].Notification.permission.asInstanceOf[String] == "granted")
      Api.notifications(unread = true).foreach(_.items.headOption.foreach { n =>
        val no = js.Dynamic.newInstance(dom.window.asInstanceOf[js.Dynamic].Notification)(n.title, js.Dynamic.literal(body = n.body.take(200), tag = n.id.toString,
          icon = "/favicon.svg"))
        no.onclick = (() => {
          dom.window.focus()
          Router.go(n.link)
        }): js.Function0[Unit]
      })

  def askDesktopPermission(): Unit = {
    val w = dom.window.asInstanceOf[js.Dynamic]
    if (!js.isUndefined(w.Notification) && w.Notification.permission.asInstanceOf[String] == "default") w.Notification.requestPermission()
  }

}

/** DOM のイベント・クリップボードの補助. */
object Dom {

  /** 入力欄（input・select・textarea）の値. */
  def value(e: dom.Event): String = e.target.asInstanceOf[js.Dynamic].value.asInstanceOf[String]

  def checked(e: dom.Event): Boolean = e.target.asInstanceOf[dom.HTMLInputElement].checked

  def copy(text: String, message: String = "コピーしました"): Unit = {
    dom.window.navigator.clipboard.writeText(text)
    Store.toast(message)
  }

}

/** 表示の補助. */
object Fmt {

  private def opts(o: (String, String)*): js.Dynamic = js.Dynamic.literal(o.map((k, v) => k -> (v: js.Any))*)

  def dateTime(t: Timestamp): String =
    t.toDate.asInstanceOf[js.Dynamic].toLocaleString("ja-JP", opts("year" -> "numeric", "month" -> "2-digit", "day" -> "2-digit",
      "hour" -> "2-digit", "minute" -> "2-digit")).asInstanceOf[String]

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
    Option(v.trim).filter(_.nonEmpty).map(new js.Date(_)).filterNot(_.getTime().isNaN).map(d => Timestamp(d.toISOString()))

}

/** 画面内のダイアログ（ブラウザ標準の confirm・prompt・alert の代わり）. 表示は parts/DialogHost.st. */
final case class DialogReq(message: String, okLabel: String, danger: Boolean, input: Option[RV[String]], cancel: Boolean, onOk: String => Unit,
                           onCancel: () => Unit)

object Dialog {

  val current: RV[Option[DialogReq]] = RV(None)

  private def open(r: DialogReq): Unit = current := Some(r)

  /** 確認（OK を押したら f. やめたら cancel）. */
  def confirm(message: String, ok: String = "OK", danger: Boolean = true, cancel: () => Unit = () => ())(f: => Unit): Unit =
    open(DialogReq(message, ok, danger, None, cancel = true, _ => f, cancel))

  /** 文字の入力（OK を押したら入力した値で f）. */
  def prompt(message: String, default: String = "", ok: String = "OK")(f: String => Unit): Unit =
    open(DialogReq(message, ok, danger = false, Some(RV(default)), cancel = true, f, () => ()))

  def alert(message: String): Unit = open(DialogReq(message, "OK", danger = false, None, cancel = false, _ => (), () => ()))

  def close(ok: Boolean): Unit = current.v.foreach { r =>
    current := None
    if (ok) r.onOk(r.input.map(_.v).getOrElse("")) else r.onCancel()
  }

}

/**
 * 保存していない変更があるときに、ほかの画面へ移る前（Router）とページを閉じる・読み直す前（beforeunload）に確かめる.
 * 画面の mounted で install し、戻り値の関数を unmounted で呼んで外す.
 */
object LeaveGuard {

  import io.github.ondeoma.scalactive.routes.Router

  def install(dirty: () => Boolean): () => Unit = {
    var leaving = false
    val removeGuard = Router.beforeEach { (to, from) =>
      if (leaving || from.forall(f => f.path == to.path && f.search == to.search) || !dirty()) Router.Continue
      else {
        Dialog.confirm("保存していない変更があります。変更を捨てて移りますか？", "捨てて移る") {
          leaving = true
          Router.go(to.path + to.search + to.hash)
        }
        Router.Cancel
      }
    }
    val unload: js.Function1[dom.BeforeUnloadEvent, Unit] = e => if (dirty()) { e.preventDefault(); e.returnValue = "" }
    dom.window.addEventListener("beforeunload", unload)
    () => { removeGuard(); dom.window.removeEventListener("beforeunload", unload) }
  }

}

/** この端末だけの表示の設定（localStorage. 使えないときは既定値）. */
object Prefs {

  def get(key: String, default: String): String =
    scala.util.Try(Option(dom.window.localStorage.getItem("smohua." + key))).toOption.flatten.getOrElse(default)

  def set(key: String, value: String): Unit = scala.util.Try(dom.window.localStorage.setItem("smohua." + key, value))

}

/** 画面の色（自動 = OS の設定、ライト、ダーク）. この端末に記憶する. */
object Theme {

  val current: RV[String] = RV(Prefs.get("theme", "system"))

  def apply(): Unit = {
    val el = dom.document.documentElement
    if (current.v == "system") el.removeAttribute("data-theme") else el.setAttribute("data-theme", current.v)
  }

  def set(v: String): Unit = { current := v; Prefs.set("theme", v); apply() }

  val options: List[(String, String)] = List("system" -> "🖥 自動（OS に合わせる）", "light" -> "☀️ ライト", "dark" -> "🌙 ダーク")

}
