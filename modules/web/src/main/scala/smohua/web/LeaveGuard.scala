package smohua.web

import org.scalajs.dom

import scala.scalajs.js

/**
 * 保存していない変更があるときに、ほかの画面へ移る前（Router）とページを閉じる・読み直す前（beforeunload）に確かめる.
 * 画面の mounted で install し、戻り値の関数を unmounted で呼んで外す.
 */
object LeaveGuard {

  import io.github.ondeoma.scalactive.routes.Router

  def install(dirty: () => Boolean): () => Unit = {
    var leaving = false
    val removeGuard = Router.beforeEach {
      (to, from) =>
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
