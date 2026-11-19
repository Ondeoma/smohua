package smohua.web

import io.github.ondeoma.scalactive.components.ComponentManager.*

object Dialog {

  val current: RV[Option[DialogReq]] = RV(None)

  private def open(
    r: DialogReq
  ): Unit = current := Some(r)

  /** 確認（OK を押したら f. やめたら cancel）. */
  def confirm(
    message: String,
    ok: String = "OK",
    danger: Boolean = true,
    cancel: () => Unit = () => ()
  )(
    f: => Unit
  ): Unit =
    open(DialogReq(message, ok, danger, None, cancel = true, _ => f, cancel))

  /** 文字の入力（OK を押したら入力した値で f）. */
  def prompt(
    message: String,
    default: String = "",
    ok: String = "OK"
  )(
    f: String => Unit
  ): Unit =
    open(DialogReq(message, ok, danger = false, Some(RV(default)), cancel = true, f, () => ()))

  def alert(
    message: String
  ): Unit = open(DialogReq(message, "OK", danger = false, None, cancel = false, _ => (), () => ()))

  def close(
    ok: Boolean
  ): Unit = current.v.foreach { r =>
    current := None
    if (ok) r.onOk(r.input.map(_.v).getOrElse("")) else r.onCancel()
  }

}
