package smohua.web

import org.scalajs.dom

import scala.scalajs.js

/** DOM のイベント・クリップボードの補助. */
object Dom {

  /** 入力欄（input・select・textarea）の値. */
  def value(
    e: dom.Event
  ): String = e.target.asInstanceOf[js.Dynamic].value.asInstanceOf[String]

  def checked(
    e: dom.Event
  ): Boolean = e.target.asInstanceOf[dom.HTMLInputElement].checked

  def copy(
    text: String,
    message: String = "コピーしました"
  ): Unit = {
    dom.window.navigator.clipboard.writeText(text)
    Store.toast(message)
  }

}
