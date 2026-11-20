<script lang="scala">
import smohua.web.*
import smohua.shared.*
import org.scalajs.dom

// フォームの画面の上の部分: 題名・状態・回答用の URL・公開と締め切りのボタン・タブ（質問・回答・設定）
case class Props(form: FormDto, tab: String, onChanged: FormDto => Unit)

def fillUrl(props: Props): String = dom.window.location.origin + Paths.fill(props.form.id)

def setStatus(props: Props, status: String): Unit =
  Toasts.report(Api.updateForm(props.form.id, UpdateFormReq(status = Some(status)))) { f =>
    Toasts.show(status match {
      case FormStatus.Open   => "公開しました。回答用の URL を渡してください"
      case FormStatus.Closed => "回答を締め切りました"
      case _                 => "下書きに戻しました"
    })
    props.onChanged(f)
  }

def close(props: Props): Unit = Dialog.confirm("回答を締め切りますか？（あとで受付を再開できます）", "締め切る", danger = false)(setStatus(props, FormStatus.Closed))

/** 受付中でも、受付期間の外なら知らせる. */
def periodNote(props: Props): String = {
  val now = scala.scalajs.js.Date.now()
  val sc = props.form.schedule
  if (props.form.status != FormStatus.Open) ""
  else if (sc.from.exists(_.toEpochMilli > now)) s"受付前（${Fmt.dateTime(sc.from.get)} から）"
  else if (sc.until.exists(_.toEpochMilli <= now)) s"受付期間が終わりました（${Fmt.dateTime(sc.until.get)}）"
  else sc.until.map(u => s"${Fmt.dateTime(u)} まで").getOrElse("")
}

def tabCls(props: Props, t: String): String = if (props.tab == t) "active" else ""
def base(props: Props): String = Paths.form(props.form.id)
</script>

<template>
  <div class="fh">
    <div class="row">
      <h1 class="grow title">{{ props.form.title }}</h1>
      <span v-if="periodNote(props).nonEmpty" class="muted small">{{ periodNote(props) }}</span>
      <span :class='"badge st-" + props.form.status'>{{ FormStatus.label(props.form.status) }}</span>
      <button v-if="props.form.canEdit && props.form.status != FormStatus.Open" class="primary" @click="setStatus(props, FormStatus.Open)">{{ if (props.form.status == FormStatus.Closed) "受付を再開する" else "公開する" }}</button>
      <button v-if="props.form.canEdit && props.form.status == FormStatus.Open" @click="close(props)">締め切る</button>
    </div>
    <div class="row share">
      <span class="muted small">回答用の URL</span>
      <code class="grow url">{{ fillUrl(props) }}</code>
      <button class="small" @click="Dom.copy(fillUrl(props), &quot;回答用の URL をコピーしました&quot;)">コピー</button>
      <a class="btn small" :href="Paths.fill(props.form.id)" target="_blank" rel="noopener">{{ if (props.form.status == FormStatus.Draft) "プレビュー" else "開く" }}</a>
    </div>
    <nav class="tabs">
      <a :href="base(props)" :class='tabCls(props, "edit")' @click.prevent="Router.go(base(props))">質問</a>
      <a :href='base(props) + "/responses"' :class='tabCls(props, "responses")' @click.prevent='Router.go(base(props) + "/responses")'>回答（{{ props.form.responseCount }}）</a>
      <a :href='base(props) + "/settings"' :class='tabCls(props, "settings")' @click.prevent='Router.go(base(props) + "/settings")'>設定</a>
    </nav>
  </div>
</template>

<style scoped>
.title { margin: 0; overflow-wrap: anywhere; }
.share { margin: .6rem 0 .8rem; background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .4rem .6rem; }
.url { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: .82rem; }
</style>
