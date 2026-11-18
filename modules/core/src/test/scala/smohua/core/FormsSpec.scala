package smohua.core

import io.circe.Json
import io.circe.syntax.*
import smohua.shared.*

class FormsSpec extends munit.FunSuite:

  private val qs = List(
    QuestionDto("name", QuestionKind.Short, "お名前", required = true),
    QuestionDto("food", QuestionKind.Single, "好きな食べ物", options = List("寿司", "カレー", "ラーメン")),
    QuestionDto("days", QuestionKind.Multi, "参加できる日", options = List("月", "火", "水")),
    QuestionDto("score", QuestionKind.Scale, "満足度", scaleMin = 1, scaleMax = 5),
    QuestionDto("when", QuestionKind.Date, "希望日"),
    QuestionDto("count", QuestionKind.Number, "人数"),
    QuestionDto("memo", QuestionKind.Long, "ひとこと")
  )

  test("質問: 整える・だめな形を断る") {
    val ok = Forms.normalize(List(QuestionDto("q1", QuestionKind.Single, " 色 ", options = List(" 赤 ", "", "赤", "青")))).toOption.get
    assertEquals(ok.head.label, "色")
    assertEquals(ok.head.options, List("赤", "青"))
    assert(Forms.normalize(List(QuestionDto("q1", QuestionKind.Single, "色"))).isLeft) // 選択肢がない
    assert(Forms.normalize(List(QuestionDto("q 1", QuestionKind.Short, "x"))).isLeft)  // id の形
    assert(Forms.normalize(List(QuestionDto("a", "file", "x"))).isLeft)                // 知らない種類
    assert(Forms.normalize(List(QuestionDto("a", QuestionKind.Short, "x"), QuestionDto("a", QuestionKind.Short, "y"))).isLeft)
    assert(Forms.normalize(List(QuestionDto("a", QuestionKind.Scale, "x", scaleMin = 1, scaleMax = 11))).isLeft)
    assert(Forms.title("  ").isLeft)
  }

  test("回答: 確かめて整える") {
    val r = Forms.validate(qs, Map(
      "name" -> " 山田 ".asJson, "food" -> "寿司".asJson, "days" -> List("水", "月").asJson, "score" -> "4".asJson,
      "when" -> "2026-11-17".asJson, "count" -> "3".asJson, "memo" -> "".asJson, "unknown" -> "x".asJson)).toOption.get
    assertEquals(r("name"), "山田".asJson)
    assertEquals(r("days"), List("月", "水").asJson) // 選択肢の並びにそろえる
    assertEquals(r("score"), 4.asJson)
    assertEquals(r("count"), 3.asJson)
    assert(!r.contains("memo"))    // 空の回答は入れない
    assert(!r.contains("unknown")) // 質問にない項目は捨てる
  }

  test("回答: 必須・選択肢・形の誤り") {
    val errs = Forms.validate(qs, Map("food" -> "パン".asJson, "days" -> List("日").asJson, "score" -> 9.asJson, "when" -> "2026-02-30".asJson,
      "count" -> "たくさん".asJson)).swap.toOption.get
    assertEquals(errs.size, 6) // name（必須）と5つの誤り
    assert(errs.head.contains("お名前"))
    assert(Forms.validate(qs, Map("name" -> Json.Null)).isLeft)
    assert(Forms.validate(qs, Map("name" -> ("あ" * 501).asJson)).isLeft)
  }

  test("集計: 件数・平均・記述") {
    val as = List(
      Map("name" -> "A".asJson, "food" -> "寿司".asJson, "days" -> List("月", "火").asJson, "score" -> 5.asJson),
      Map("name" -> "B".asJson, "food" -> "寿司".asJson, "days" -> List("火").asJson, "score" -> 2.asJson),
      Map("name" -> "C".asJson, "food" -> "そば".asJson))
    val s = Forms.summarize(qs, as).map(x => x.id -> x).toMap
    assertEquals(s("food").counts, List(CountDto("寿司", 2), CountDto("カレー", 0), CountDto("ラーメン", 0), CountDto("そば", 1)))
    assertEquals(s("days").counts, List(CountDto("月", 1), CountDto("火", 2), CountDto("水", 0)))
    assertEquals(s("score").average, Some(3.5))
    assertEquals(s("score").counts.map(_.count), List(0, 1, 0, 0, 1))
    assertEquals(s("name").texts, List("A", "B", "C"))
    assertEquals(s("name").answered, 3)
    assertEquals(s("memo").answered, 0)
  }

  test("CSV: 区切り・引用符・式の無害化") {
    assertEquals(Forms.csvLine(List("a,b", "c\"d", "=1+1", "普通")), "\"a,b\",\"c\"\"d\",'=1+1,普通\r\n")
    assertEquals(Forms.csvHeader(qs).take(4), List("番号", "回答日時", "回答者", "お名前"))
    assertEquals(Forms.digest(qs, Map("name" -> "山田".asJson, "days" -> List("月", "水").asJson)), "お名前: 山田\n参加できる日: 月、水")
  }

  test("見た目: 色の形・文言の長さ・外のサイトを読み込む CSS を断る") {
    val ok = Forms.appearance(FormAppearanceDto(background = " #FFF ", accent = "#0f766e", submitLabel = " 申し込む ", css = ".fill h1 { color: red; }"))
    assertEquals(ok.map(a => (a.background, a.accent, a.submitLabel)), Right(("#fff", "#0f766e", "申し込む")))
    assert(Forms.appearance(FormAppearanceDto(text = "red")).isLeft)
    assert(Forms.appearance(FormAppearanceDto(submitLabel = "あ" * 31)).isLeft)
    // data: は使える
    assert(Forms.appearance(FormAppearanceDto(css = ".x { background: url(data:image/png;base64,AAAA) }")).isRight)
    List(
      "@import 'x.css';",
      "@im/**/port url(x.css);",
      ".x { background: url(https://evil.example/t.png) }",
      ".x { background: url( '//evil.example/t.png') }",
      ".x { background: \\75 rl(http://evil.example/t.png) }",
      ".x { background: image-set(\"https://evil.example/t.png\" 1x) }",
      ".x { content: '</style>' }"
    ).foreach(css => assert(Forms.appearance(FormAppearanceDto(css = css)).isLeft, css))
  }

  test("受付期間: 終わりは始まりより後") {
    val t = java.time.Instant.parse("2026-12-01T00:00:00Z")
    assert(Forms.schedule(FormScheduleDto(Some(t), Some(t.plusSeconds(60)))).isRight)
    assert(Forms.schedule(FormScheduleDto(Some(t), Some(t))).isLeft)
    assert(Forms.schedule(FormScheduleDto(None, Some(t))).isRight)
  }

  test("題名: 改行などの制御文字は空白にする（メールの件名・CSV に入るため）") {
    assertEquals(Forms.title(" 懇親会\r\nBcc: x@example.com\t"), Right("懇親会  Bcc: x@example.com"))
    assert(Forms.title("\n\t").isLeft)
  }

  test("戻り先: 同じサイトのパスだけ（//・/\\・制御文字・絶対 URL は断る）") {
    List("/", "/forms/1?x=//a", "/f/abc").foreach(p => assert(Paths.isLocal(p), p))
    List("", "//evil.example", "/\\evil.example", "https://evil.example/", "javascript:alert(1)", "/a\nb").foreach(p => assert(!Paths.isLocal(p), p))
  }
