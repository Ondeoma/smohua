<script lang="scala">
import smohua.web.*
import smohua.web.parts.*
import smohua.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// フォームの編集: 題名・説明と、質問の追加・並べ替え・削除. 「保存」を押すまでは保存しない
// （保存していない変更があれば、ほかの画面へ移る前・ページを閉じる前に確かめる）.

// 編集中の質問（入力欄ごとに値を持つ. 選択肢は1行に1つ）
case class QEdit(id: String, kind: RV[String], label: RV[String], help: RV[String], required: RV[Boolean], options: RV[String],
                 min: RV[String], max: RV[String])

case class Props(formId: UUID)

case class State(form: RV[Option[FormDto]] = RV(None), title: RV[String] = RV(""), desc: RV[String] = RV(""), qs: RV[List[QEdit]] = RV(Nil),
                 saving: RV[Boolean] = RV(false), newKind: RV[String] = RV(QuestionKind.Short), saved: RV[String] = RV(""),
                 unguard: RV[Option[() => Unit]] = RV(None))

def initState() = State()

def edit(q: QuestionDto): QEdit =
  QEdit(q.id, RV(q.kind), RV(q.label), RV(q.help), RV(q.required), RV(q.options.mkString("\n")), RV(q.scaleMin.toString), RV(q.scaleMax.toString))

def toDto(q: QEdit): QuestionDto =
  QuestionDto(q.id, q.kind.v, q.label.v, q.help.v, q.required.v, q.options.v.split('\n').toList.map(_.trim).filter(_.nonEmpty),
    q.min.v.toIntOption.getOrElse(1), q.max.v.toIntOption.getOrElse(5))

/** 保存する内容（変更があるかの比べ合わせにも使う）. */
def request(s: State): UpdateFormReq = UpdateFormReq(title = Some(s.title.v), description = Some(s.desc.v), questions = Some(s.qs.v.map(toDto)))
def snapshot(s: State): String = request(s).toString

def show(s: State, f: FormDto): Unit = {
  s.form := Some(f); s.title := f.title; s.desc := f.description; s.qs := f.questions.map(edit)
  s.saved := snapshot(s)
  pageTitle(f.title)
}

/** 保存していない変更があるか（編集できない人は保存できないので確かめない）. */
def dirty(s: State): Boolean = s.form.v.exists(_.canEdit) && snapshot(s) != s.saved.v

def mounted(s: State, props: Props): Unit = {
  Store.report(Api.form(props.formId))(show(s, _))
  s.unguard := Some(LeaveGuard.install(() => dirty(s)))
}

def unmounted(s: State): Unit = { s.unguard.v.foreach(_()); s.unguard := None }

// 回答は質問の id に結び付くので、id は一度決めたら変えない
def newId(): String = "q" + (1 to 8).map(_ => "abcdefghijklmnopqrstuvwxyz0123456789".charAt((scala.scalajs.js.Math.random() * 36).toInt)).mkString

def add(s: State): Unit = {
  val k = s.newKind.v
  s.qs := s.qs.v :+ QEdit(newId(), RV(k), RV(""), RV(""), RV(false), RV(if (QuestionKind.hasOptions(k)) "選択肢 1\n選択肢 2" else ""), RV("1"), RV("5"))
}

def copy(s: State, q: QEdit): Unit = {
  val (a, b) = s.qs.v.span(_.id != q.id)
  s.qs := a ++ b.take(1) ++ List(edit(toDto(q).copy(id = newId()))) ++ b.drop(1)
}

def remove(s: State, q: QEdit): Unit = s.qs := s.qs.v.filterNot(_.id == q.id)

def move(s: State, q: QEdit, d: Int): Unit = {
  val l = s.qs.v
  val i = l.indexWhere(_.id == q.id)
  val j = i + d
  if (i >= 0 && j >= 0 && j < l.size) s.qs := l.updated(i, l(j)).updated(j, l(i))
}

def save(s: State, props: Props): Unit = {
  s.saving := true
  Api.updateForm(props.formId, request(s)).onComplete { r =>
    s.saving := false
    r.fold(Store.error, f => { show(s, f); Store.toast("保存しました") })
  }
}

def kinds: List[(String, String)] = QuestionKind.all.map(k => k -> QuestionKind.label(k))
def forms(s: State) = s.form.mapC(_.toList)
def noQuestions(s: State) = s.qs.mapC(_.isEmpty)
def canEdit(s: State) = s.form.mapC(_.exists(_.canEdit))
def hasResponses(s: State) = s.form.mapC(_.exists(_.responseCount > 0))
def nums(from: Int, to: Int): List[String] = (from to to).toList.map(_.toString)
</script>

<template>
  <div class="page narrow">
    <FormHeader v-for="f in forms(s)" :form="f" tab="edit" :onChanged="g => s.form := Some(g)" />
    <div v-if="s.form.mapC(_.isDefined)">
      <section class="panel intro">
        <label class="field"><span>題名</span><input type="text" v-model="s.title" maxlength="200" class="big"></label>
        <label class="field"><span>説明（回答する人に見せる文）</span><textarea v-model="s.desc" maxlength="5000" placeholder="このフォームの目的や、回答の期限など"></textarea></label>
      </section>
      <p v-if="hasResponses(s)" class="muted small note">すでに回答があります。質問文や選択肢を変えても、前の回答はそのまま残ります（消した質問への回答は、一覧と集計に出なくなります）。</p>
      <p v-if="noQuestions(s)" class="empty card">質問はまだありません。下の「質問を追加」から作ってください。</p>
      <section v-for="q in s.qs" :key="q.id" class="panel q">
        <div class="row">
          <input type="text" v-model="q.label" placeholder="質問文" maxlength="300" class="grow label">
          <select v-model="q.kind" title="質問の種類"><option v-for="k in kinds" :value="k._1">{{ k._2 }}</option></select>
        </div>
        <input type="text" v-model="q.help" placeholder="補足（任意。質問の下に小さく出します）" class="help">
        <label v-if="q.kind.mapC(QuestionKind.hasOptions)" class="field"><span>選択肢（1行に1つ）</span><textarea v-model="q.options" rows="4"></textarea></label>
        <div v-if="q.kind.mapC(_ == QuestionKind.Scale)" class="row small">
          <select v-model="q.min"><option v-for="n in nums(0, 1)" :value="n">{{ n }}</option></select> から
          <select v-model="q.max"><option v-for="n in nums(2, 10)" :value="n">{{ n }}</option></select> までの段階
        </div>
        <div class="row tools">
          <label class="check"><input type="checkbox" v-model="q.required"> 必須</label>
          <span class="grow"></span>
          <button class="ghost small" title="上へ" @click="move(s, q, -1)">↑</button>
          <button class="ghost small" title="下へ" @click="move(s, q, 1)">↓</button>
          <button class="ghost small" @click="copy(s, q)">複製</button>
          <button class="ghost small danger" @click="remove(s, q)">削除</button>
        </div>
      </section>
      <div v-if="canEdit(s)" class="row bar">
        <select v-model="s.newKind"><option v-for="k in kinds" :value="k._1">{{ k._2 }}</option></select>
        <button @click="add(s)">＋ 質問を追加</button>
        <span class="grow"></span>
        <button class="primary" :disabled="s.saving" @click="save(s, props)">保存</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.big { font-size: 1.15rem; font-weight: 600; }
.intro { border-top: 4px solid var(--accent); }
.q { display: flex; flex-direction: column; gap: .5rem; }
.q .label { font-weight: 600; }
.q .help { font-size: .85rem; }
.q .field { margin: 0; }
.tools { border-top: 1px solid var(--line); padding-top: .4rem; }
.bar { position: sticky; bottom: 0; background: var(--bg); padding: .7rem 0; border-top: 1px solid var(--line); }
.note { margin: 0 0 .8rem; }
</style>
