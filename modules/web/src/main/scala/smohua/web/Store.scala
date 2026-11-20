package smohua.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import smohua.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

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
  val changes: RV[Change] = RV(Change(0, None, ""))

  private var changeSeq = 0

  def myId: Option[UUID] = me.v.map(_.id)

  /** システム管理者か. */
  def isAdmin = me.mapC(_.exists(_.isAdmin))

  // ---------------------------------------------------------------- 自動同期（SSE）

  private var stream: Option[dom.EventSource] = None
  private var lastSync = 0.0
  // 画面が隠れている間に流さなかった知らせがあるか（見えるようになったとき、まとめて読み直す）
  private var missed = false

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
  private def flush(): Unit = pending.foreach {
    (f, k) =>
      pending = None
      if (dom.document.visibilityState.toString == "visible") {
        changeSeq += 1
        changes := Change(changeSeq, f, k)
      } else missed = true
  }

  /** 未読の通知の数を読み直す（増えていて、画面が隠れていればデスクトップにも出す）. */
  def refreshUnread(): Unit = if (me.v.isDefined) Api.unread().foreach { n =>
    if (n > unread.v) desktopNotify()
    if (n != unread.v) unread := n
  }

  /** まとめて読み直す（つなぎ直したとき・SSE がつながらないとき）. */
  def sync(): Unit = if (me.v.isDefined) {
    lastSync = js.Date.now()
    missed = false
    refreshUnread()
    emit(None, "sync")
  }

  def connect(): Unit = if (me.v.isDefined && stream.isEmpty) {
    val es = new dom.EventSource("/api/stream")
    es.addEventListener(
      "change",
      (e: dom.MessageEvent) => {
        val d = js.JSON.parse(e.data.toString)
        emit(scala.util.Try(UUID.fromString(d.form.toString)).toOption, d.kind.toString)
      }
    )
    es.addEventListener("notify", (_: dom.MessageEvent) => refreshUnread())
    // つなぎ直したときは取りこぼしを確かめる
    es.addEventListener("open", (_: dom.Event) => sync())
    stream = Some(es)
  }

  /**
   * 画面が見えている間だけ、10秒ごとに確かめる（隠れている間は止め、見えるようになったときはすぐ確かめる）.
   * SSE でつながっている間は、念のため60秒ごとにだけ読み直す.
   */
  def startSync(): Unit = {
    useVisiblePolling(10000) {
      // サーバーに断られて（429 など）ブラウザがつなぎ直すのをやめた SSE は、作り直す
      if (stream.exists(_.readyState == dom.EventSource.CLOSED)) { stream = None; connect() }
      if (missed || !live || js.Date.now() - lastSync > 60000) sync()
    }
    ()
  }

  /** 画面が隠れているときに、新しい通知をデスクトップに出す（許可されていれば）. */
  private def desktopNotify(): Unit =
    if (
      dom.document.visibilityState.toString != "visible" && Prefs.get("desktop", "on") == "on" &&
      !js.isUndefined(dom.window.asInstanceOf[js.Dynamic].Notification) &&
      dom.window.asInstanceOf[js.Dynamic].Notification.permission.asInstanceOf[String] == "granted"
    )
      Api.notifications(unread = true).foreach(_.items.headOption.foreach { n =>
        val no = js.Dynamic.newInstance(dom.window.asInstanceOf[js.Dynamic].Notification)(
          n.title,
          js.Dynamic.literal(
            body = n.body.take(200),
            tag = n.id.toString,
            icon = "/favicon.svg"
          )
        )
        no.onclick = (
          () => {
            dom.window.focus()
            Router.go(n.link)
          }): js.Function0[Unit]
      })

  def askDesktopPermission(): Unit = {
    val w = dom.window.asInstanceOf[js.Dynamic]
    if (!js.isUndefined(w.Notification) && w.Notification.permission.asInstanceOf[String] == "default")
      w.Notification.requestPermission()
  }

}

/** この端末だけの表示の設定（localStorage. 使えないときは既定値）. */
object Prefs extends sbirds.web.LocalPrefs("smohua")

/** 画面の色（自動 = OS の設定、ライト、ダーク）. この端末に記憶する. */
object Theme extends sbirds.web.ThemeSwitch(Prefs)
