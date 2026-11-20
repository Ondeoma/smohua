<script lang="scala">
import smohua.web.*
import smohua.web.parts.*
import smohua.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 集めた回答: 質問ごとの集計と、回答の一覧. 新しい回答が届くと自動で読み直す.
case class Props(formId: UUID)

case class State(form: RV[Option[FormDto]] = RV(None), stats: RV[Option[FormSummaryStatsDto]] = RV(None), rows: RV[List[ResponseDto]] = RV(Nil),
                 total: RV[Int] = RV(0), view: RV[String] = RV(Prefs.get("responses.view", "summary")), detail: RV[Option[ResponseDto]] = RV(None),
                 statsKey: RV[String] = RV(""))

def initState() = State()

/**
 * 読み直す. kind は変更の種類（response.… なら回答だけ、form.… ならフォームだけ、sync はすべて）.
 * 集計は重いので、回答の数・いちばん新しい回答・フォームの更新日時のどれかが変わったときだけ読み直す.
 */
def load(s: State, props: Props, kind: String = "sync"): Unit = {
  val form = if (kind.startsWith("response.")) Future.successful(None) else Api.form(props.formId).map(Some(_))
  // 読み込んである件数までを読み直す（少なくとも100件）
  val rows = if (kind.startsWith("form.")) Future.successful(None)
             else Api.responses(props.formId, None, math.min(500, math.max(100, s.rows.v.size))).map(Some(_))
  Toasts.report(form.zip(rows)) { (f, p) =>
    f.foreach { x => s.form := Some(x); pageTitle(x.title + " の回答") }
    p.foreach { x =>
      s.rows := x.responses; s.total := x.total; Store.refreshUnread()
      // タブの「回答（N）」はフォームの件数なので、フォームを読み直さないときもそろえる
      if (s.form.v.exists(_.responseCount != x.total)) s.form := s.form.v.map(_.copy(responseCount = x.total))
    }
    val key = s"${s.total.v}:${s.rows.v.headOption.map(_.id)}:${s.form.v.map(_.updatedAt)}"
    if (key != s.statsKey.v) {
      s.statsKey := key
      Toasts.report(Api.summary(props.formId))(st => s.stats := Some(st))
    }
  }
}

def mounted(s: State, props: Props): Unit = {
  load(s, props)
  watch(Store.changes, false)((_, c) => if (c.formId.forall(_ == props.formId)) load(s, props, c.kind))
}

def more(s: State, props: Props): Unit =
  Api.responses(props.formId, s.rows.v.lastOption.map(_.seq), 100).foreach(p => s.rows := s.rows.v ++ p.responses)

def setView(s: State, v: String): Unit = { s.view := v; Prefs.set("responses.view", v) }

def remove(s: State, props: Props, r: ResponseDto): Unit = Dialog.confirm(s"回答 #${r.seq} を削除しますか？", "削除する") {
  Toasts.report(Api.deleteResponse(props.formId, r.id)) { _ => s.detail := None; Toasts.show("回答を削除しました"); load(s, props) }
}

def forms(s: State) = s.form.mapC(_.toList)
def questions(s: State) = s.form.mapC(_.map(_.questions).getOrElse(Nil))
def summaries(s: State) = s.stats.mapC(_.map(_.questions).getOrElse(Nil))
def isView(s: State, v: String) = s.view.mapC(_ == v)
def viewCls(s: State, v: String) = s.view.mapC(x => if (x == v) "chip active" else "chip")
def empty(s: State) = computed { s.total.v == 0 && s.form.v.isDefined }
def hasMore(s: State) = computed { s.rows.v.size < s.total.v }
def canEdit(s: State) = s.form.mapC(_.exists(_.canEdit))
def who(r: ResponseDto): String = r.respondent.map(_.name).getOrElse("匿名")
def csvUrl(props: Props): String = s"/api/forms/${props.formId}/responses.csv"
/** 棒の長さ（いちばん多い選択肢を 100% にする）. */
def width(q: QuestionSummaryDto, c: CountDto): String = {
  val max = math.max(1, q.counts.map(_.count).max)
  s"width: ${c.count * 100 / max}%"
}
def percent(q: QuestionSummaryDto, c: CountDto): String = if (q.answered == 0) "" else s"${math.round(c.count * 100.0 / q.answered)}%"
def cell(r: ResponseDto, q: QuestionDto): String = Answers.of(r.answers, q)
</script>

<template>
  <div class="page">
    <FormHeader v-for="f in forms(s)" :form="f" tab="responses" :onChanged="g => s.form := Some(g)" />
    <div v-if="empty(s)" class="empty card">
      <p>まだ回答はありません。フォームを公開して、回答用の URL を渡してください。</p>
    </div>
    <div v-else>
      <div class="row bar">
        <div class="chips">
          <button :class='viewCls(s, "summary")' @click='setView(s, "summary")'>集計</button>
          <button :class='viewCls(s, "list")' @click='setView(s, "list")'>一覧</button>
        </div>
        <span class="muted small">{{ s.total }} 件の回答</span>
        <span class="grow"></span>
        <a class="btn small" :href="csvUrl(props)" download>CSV をダウンロード</a>
      </div>

      <div v-if='isView(s, "summary")'>
        <section v-for="q in summaries(s)" :key="q.id" class="panel">
          <div class="row"><h3 class="grow">{{ q.label }}</h3><span class="muted small">{{ q.answered }} 件の回答</span></div>
          <p v-for="a in q.average.toList" class="avg">平均 <b>{{ a }}</b></p>
          <div v-for="c in q.counts" class="bar-row">
            <span class="bar-label">{{ c.label }}</span>
            <span class="bar-track"><span class="bar-fill" :style="width(q, c)"></span></span>
            <span class="bar-num">{{ c.count }} <span class="muted small">{{ percent(q, c) }}</span></span>
          </div>
          <ul v-if="q.texts.nonEmpty" class="texts"><li v-for="t in q.texts">{{ t }}</li></ul>
          <p v-if="q.answered == 0" class="muted small">回答はありません</p>
        </section>
      </div>

      <div v-if='isView(s, "list")'>
        <div class="table-wrap">
          <table class="list">
            <thead><tr><th>番号</th><th>回答日時</th><th>回答者</th><th v-for="q in questions(s)">{{ q.label }}</th></tr></thead>
            <tbody>
              <tr v-for="r in s.rows" :key="r.id" class="clickable" @click="s.detail := Some(r)">
                <td class="key">#{{ r.seq }}</td>
                <td class="small nowrap">{{ Fmt.dateTime(r.createdAt) }}</td>
                <td class="nowrap"><Avatar v-if="r.respondent.isDefined" :user="r.respondent.map(Fmt.person)" :size="20" /> {{ who(r) }}</td>
                <td v-for="q in questions(s).v" class="cell">{{ cell(r, q) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="hasMore(s)" class="more"><button @click="more(s, props)">さらに読み込む</button></p>
      </div>
    </div>

    <Modal v-for="r in s.detail.mapC(_.toList)" :onClose="_ => s.detail := None">
      <div class="row"><h2 class="grow">回答 #{{ r.seq }}</h2><button class="ghost" @click="s.detail := None">×</button></div>
      <p class="muted small">{{ Fmt.dateTime(r.createdAt) }}・{{ who(r) }}</p>
      <dl class="answers">
        <template v-for="q in questions(s).v">
          <dt>{{ q.label }}</dt>
          <dd>{{ cell(r, q) }}</dd>
        </template>
      </dl>
      <div class="actions"><button v-if="canEdit(s)" class="danger" @click="remove(s, props, r)">この回答を削除</button></div>
    </Modal>
  </div>
</template>

<style scoped>
.bar { margin-bottom: 1rem; }
.panel h3 { margin: 0 0 .5rem; overflow-wrap: anywhere; }
.avg { margin: 0 0 .5rem; }
.bar-row { display: grid; grid-template-columns: minmax(6rem, 14rem) 1fr 5.5rem; gap: .6rem; align-items: center; padding: .18rem 0; font-size: .9rem; }
.bar-label { overflow-wrap: anywhere; }
.bar-track { background: var(--hover); border-radius: 5px; height: 1.1rem; overflow: hidden; }
.bar-fill { display: block; height: 100%; background: var(--accent); border-radius: 5px; min-width: 2px; transition: width .3s; }
.bar-num { text-align: right; white-space: nowrap; }
.texts { margin: .2rem 0 0; padding-left: 1.2rem; max-height: 18rem; overflow-y: auto; }
.texts li { margin: .25rem 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.cell { max-width: 18rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.more { text-align: center; }
.answers dt { font-size: .8rem; color: var(--muted); margin-top: .7rem; }
.answers dd { margin: .15rem 0 0; white-space: pre-wrap; overflow-wrap: anywhere; min-height: 1.2em; }
@media (max-width: 720px) { .bar-row { grid-template-columns: 1fr 4.5rem; } .bar-label { grid-column: 1 / -1; } }
</style>
