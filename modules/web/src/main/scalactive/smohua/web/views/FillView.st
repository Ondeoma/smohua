<script lang="scala">
import smohua.web.*
import smohua.shared.*
import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 回答する画面（回答用の URL で開く）. ログインが要るかどうかは、フォームの設定による.

// 質問1つ分の入力（複数選ぶ質問は multi、ほかは text に持つ）
case class Field(q: QuestionDto, text: RV[String], multi: RV[List[String]])

case class Props(formId: UUID)

case class State(form: RV[Option[PublicFormDto]] = RV(None), fields: RV[List[Field]] = RV(Nil), error: RV[String] = RV(""),
                 notFound: RV[Boolean] = RV(false), sending: RV[Boolean] = RV(false), done: RV[Option[String]] = RV(None))

def initState() = State()

def load(s: State, props: Props): Unit = Api.publicForm(props.formId).onComplete {
  case Success(f) =>
    s.form := Some(f); s.fields := f.questions.map(q => Field(q, RV(""), RV(Nil)))
    FormLook(f.appearance)
    pageTitle(f.title)
  case Failure(_) => s.notFound := true
}

def mounted(s: State, props: Props): Unit = load(s, props)

def unmounted(s: State): Unit = FormLook.clear()

def value(f: Field): Option[Json] =
  if (f.q.kind == QuestionKind.Multi) Option.when(f.multi.v.nonEmpty)(f.multi.v.asJson)
  else Option(f.text.v.trim).filter(_.nonEmpty).map(_.asJson)

def submit(s: State, props: Props): Unit = {
  s.error := ""; s.sending := true
  Api.submit(props.formId, s.fields.v.flatMap(f => value(f).map(f.q.id -> _)).toMap).onComplete { r =>
    s.sending := false
    r match {
      case Success(d) => s.done := Some(d.confirmation); dom.window.scrollTo(0, 0)
      case Failure(e) => s.error := e.getMessage
    }
  }
}

def again(s: State, props: Props): Unit = { s.done := None; load(s, props) }

def loginUrl(props: Props): String = "/auth/login?returnTo=" + Api.enc(Paths.fill(props.formId))
/**
 * ほかのサイトに埋め込まれているか. 埋め込んだ画面には Cookie が送られず（SameSite）、IdP の画面も iframe には出せないので、
 * ログインは新しいタブで開き、そのタブで回答してもらう.
 */
def framed: Boolean = FormLook.embedded || scala.util.Try(dom.window.top != dom.window).getOrElse(true)
def loginTarget: String = if (framed) "_blank" else "_self"

def forms(s: State) = s.form.mapC(_.toList)
/** 入力欄を出すか（受付中、または下書きのプレビュー）. */
def canAnswer(f: PublicFormDto): Boolean = (f.phase == FormPhase.Open || f.phase == FormPhase.Draft) && !f.needLogin && !f.answered
/** 受付が終わっているか（締め切り、または受付期間が過ぎた）. */
def ended(f: PublicFormDto): Boolean = f.phase == FormPhase.Closed || f.phase == FormPhase.After
def fromText(f: PublicFormDto): String = f.schedule.from.map(Fmt.dateTime).getOrElse("")
def untilText(f: PublicFormDto): String = f.schedule.until.map(Fmt.dateTime).getOrElse("")
/** 題名・説明・知らせのどれかを出すときだけ、上のカードを出す. */
def showIntro(f: PublicFormDto): Boolean =
  f.appearance.showTitle || (f.appearance.showDescription && f.description.nonEmpty) || f.phase != FormPhase.Open || f.needLogin || f.answered ||
    f.schedule.until.isDefined
def submitLabel(f: PublicFormDto): String = if (f.appearance.submitLabel.trim.isEmpty) "送信" else f.appearance.submitLabel
def branding(s: State) = s.form.mapC(_.forall(_.appearance.showBranding))
def scale(q: QuestionDto): List[String] = (q.scaleMin to q.scaleMax).toList.map(_.toString)
def is(f: Field, kinds: String*): Boolean = kinds.contains(f.q.kind)
def thanks(s: State) = s.done.mapC(_.map(c => if (c.trim.isEmpty) "回答を受け付けました。ありがとうございました。" else c).getOrElse(""))
def showForm(s: State) = computed { s.done.v.isEmpty && s.form.v.isDefined }
</script>

<template>
  <div class="page narrow fill">
    <div v-if="s.notFound" class="card center">
      <img src="/logo.svg" alt="" width="80" height="80">
      <h1>フォームが見つかりません</h1>
      <p class="muted">URL が正しいか確かめてください。まだ公開されていないか、削除された可能性があります。</p>
    </div>

    <div v-if="s.done.mapC(_.isDefined)" class="card center">
      <div class="check-mark">✓</div>
      <p class="thanks">{{ thanks(s) }}</p>
      <p v-for="f in forms(s)"><button v-if="!f.oneResponse" class="small" @click="again(s, props)">別の回答を送る</button></p>
    </div>

    <div v-if="showForm(s)">
      <template v-for="f in forms(s)">
        <section v-if="showIntro(f)" :class='if (f.appearance.showTitle) "card intro" else "card"'>
          <h1 v-if="f.appearance.showTitle">{{ f.title }}</h1>
          <p v-if="f.appearance.showDescription && f.description.nonEmpty" class="desc">{{ f.description }}</p>
          <p v-if="f.phase == FormPhase.Draft" class="notice">下書きのプレビューです（公開するまで、ほかの人は回答できません）。</p>
          <p v-if="f.phase == FormPhase.Before" class="notice">このフォームは {{ fromText(f) }} から回答を受け付けます。</p>
          <p v-if="ended(f)" class="notice">このフォームは回答を締め切りました。</p>
          <p v-if="f.phase == FormPhase.Open && f.schedule.until.isDefined" class="muted small until">{{ untilText(f) }} まで受け付けます。</p>
          <div v-if="f.needLogin && !ended(f)">
            <p class="muted">このフォームに回答するには、ログインが必要です。</p>
            <p v-if="framed" class="muted small">埋め込まれた画面ではログインできないため、新しいタブで開きます。ログインした後、そのタブで回答してください。</p>
            <a class="btn primary" :href="loginUrl(props)" :target="loginTarget" rel="noopener">ログインして回答する</a>
          </div>
          <p v-if="f.answered" class="notice">このフォームには回答済みです。</p>
        </section>

        <form v-if="canAnswer(f)" @submit.prevent="submit(s, props)">
          <section v-for="x in s.fields" :key="x.q.id" class="card q">
            <div class="label">{{ x.q.label }}<span v-if="x.q.required" class="req" title="必須"> *</span></div>
            <div v-if="x.q.help.nonEmpty" class="muted small help">{{ x.q.help }}</div>
            <input v-if="is(x, QuestionKind.Short)" type="text" v-model="x.text" maxlength="500" class="wide">
            <textarea v-if="is(x, QuestionKind.Long)" v-model="x.text" rows="4" maxlength="10000"></textarea>
            <input v-if="is(x, QuestionKind.Number)" type="number" step="any" v-model="x.text">
            <input v-if="is(x, QuestionKind.Date)" type="date" v-model="x.text">
            <div v-if="is(x, QuestionKind.Single)" class="opts">
              <label v-for="o in x.q.options" class="check"><input type="radio" :name="x.q.id" :value="o" v-model="x.text"> {{ o }}</label>
            </div>
            <div v-if="is(x, QuestionKind.Multi)" class="opts">
              <label v-for="o in x.q.options" class="check"><input type="checkbox" :value="o" v-model="x.multi"> {{ o }}</label>
            </div>
            <select v-if="is(x, QuestionKind.Dropdown)" v-model="x.text">
              <option value="">選んでください</option>
              <option v-for="o in x.q.options" :value="o">{{ o }}</option>
            </select>
            <div v-if="is(x, QuestionKind.Scale)" class="scale">
              <label v-for="n in scale(x.q)"><input type="radio" :name="x.q.id" :value="n" v-model="x.text"><span>{{ n }}</span></label>
            </div>
          </section>
          <p v-if="s.error.mapC(_.nonEmpty)" class="error errs">{{ s.error }}</p>
          <div class="row">
            <button type="submit" class="primary send" :disabled="s.sending">{{ submitLabel(f) }}</button>
            <span v-if="f.access == FormAccess.Anyone && f.appearance.showAnonymous" class="muted small">この回答は匿名で送られます</span>
          </div>
        </form>
      </template>
    </div>
    <p v-if="branding(s)" class="powered muted small">smohua のフォーム</p>
  </div>
</template>

<style scoped>
.fill { max-width: 720px; }
.card { margin-bottom: .9rem; }
.intro { border-top: 6px solid var(--accent); }
.intro h1 { margin: .2rem 0 .6rem; overflow-wrap: anywhere; }
.desc { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0 0 .4rem; }
.until { margin: .5rem 0 0; }
.notice { background: var(--accent-weak); border-radius: 8px; padding: .5rem .8rem; margin: .6rem 0 0; }
.q { display: flex; flex-direction: column; gap: .5rem; }
.label { font-weight: 600; overflow-wrap: anywhere; }
.req { color: var(--danger); }
.help { margin-top: -.3rem; white-space: pre-wrap; }
.wide { width: 100%; }
.opts { display: flex; flex-direction: column; }
.opts .check { font-size: .95rem; padding: .15rem 0; cursor: pointer; }
.scale { display: flex; gap: .3rem; flex-wrap: wrap; }
.scale label { display: flex; flex-direction: column-reverse; align-items: center; gap: .15rem; min-width: 2.4rem; padding: .3rem; border: 1px solid var(--line); border-radius: 8px; cursor: pointer; }
.scale label:has(input:checked) { border-color: var(--accent); background: var(--accent-weak); }
.errs { white-space: pre-wrap; }
.send { padding: .5rem 1.6rem; font-size: 1rem; }
.center { text-align: center; padding: 2rem 1rem; }
.check-mark { width: 3.4rem; height: 3.4rem; border-radius: 50%; background: var(--ok); color: #fff; font-size: 2rem; line-height: 3.4rem; margin: 0 auto .8rem; }
.thanks { white-space: pre-wrap; font-size: 1.05rem; }
.powered { text-align: center; margin-top: 2rem; }
</style>
