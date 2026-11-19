package smohua.web

import org.scalajs.dom
import smohua.shared.*

/** 回答の画面の見た目（フォームごとの色と CSS）を、ページに足したり外したりする. */
object FormLook {

  private val Id = "smohua-form-look"

  /** iframe で埋め込んだとき（?embed=1）. ヘッダーを出さない. */
  def embedded: Boolean = new dom.URLSearchParams(dom.window.location.search).has("embed")

  /** 色の設定を CSS の変数に置き換えた CSS（設定した色だけ. 文字色から、薄い文字・線の色も作る）. */
  def css(
    a: FormAppearanceDto
  ): String = {
    def mix(
      c: String,
      p: Int
    ) = s"color-mix(in srgb, $c $p%, transparent)"
    val vars = List(
      // 入力欄・ラジオボタンなどのブラウザの部品も、背景の明るさに合わせる
      Option(a.background).filter(_.nonEmpty).map(c =>
        s"--bg: $c; color-scheme: ${if (light(c)) "light" else "dark"};"
      ),
      Option(a.card).filter(_.nonEmpty).map(c => s"--card: $c; --surface: $c;"),
      Option(a.text).filter(_.nonEmpty).map(c =>
        s"--fg: $c; --muted: ${mix(c, 68)}; --line: ${mix(c, 18)}; color: $c;"
      ),
      Option(a.accent).filter(_.nonEmpty).map(c => s"--accent: $c; --accent-weak: ${mix(c, 14)};")
    ).flatten
    // テーマの変数は :root に付いているので、body で上書きする（body の中だけに効く）
    (if (vars.isEmpty) "" else vars.mkString("body {\n  ", "\n  ", "\n  background: var(--bg);\n}\n")) + a.css
  }

  /** #rgb・#rrggbb の色が明るいか（相対輝度で見る）. */
  def light(
    hex: String
  ): Boolean = {
    val h = hex.stripPrefix("#")
    val full = if (h.length == 3) h.flatMap(c => s"$c$c") else h
    scala.util.Try {
      val List(r, g, b) = full.grouped(2).map(Integer.parseInt(_, 16) / 255.0).toList
      0.2126 * r + 0.7152 * g + 0.0722 * b > 0.5
    }.getOrElse(true)
  }

  def apply(
    a: FormAppearanceDto
  ): Unit = {
    val el = Option(dom.document.getElementById(Id)).getOrElse {
      val e = dom.document.createElement("style"); e.id = Id; dom.document.head.appendChild(e); e
    }
    el.textContent = css(a) // 文字として入れる（HTML としては解釈しない）
  }

  def clear(): Unit = Option(dom.document.getElementById(Id)).foreach(e => e.parentNode.removeChild(e))

}
