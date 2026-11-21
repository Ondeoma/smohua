<script lang="scala">
import smohua.web.*
import smohua.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// フォームの通知先（チャットの受信 Webhook・署名付きの Webhook・メールアドレス）. フォームの設定の画面に置く（持ち主と管理者だけ）.
// 編集中の値は1つずつの RV に持つ（入力のたびにモーダルを作り直さないように）.
case class Props(formId: UUID)

case class State(targets: RV[List[FormTargetDto]] = RV(Nil),
                 open: RV[Boolean] = RV(false), editId: RV[Option[UUID]] = RV(None),
                 name: RV[String] = RV(""), kind: RV[String] = RV(TargetKind.ChatHook), url: RV[String] = RV(""),
                 address: RV[String] = RV(""), onResponse: RV[Boolean] = RV(true), onOpen: RV[Boolean] = RV(false),
                 onClose: RV[Boolean] = RV(false), enabled: RV[Boolean] = RV(true),
                 testing: RV[Option[UUID]] = RV(None))

def initState(props: Props) = State()

def mounted(s: State, props: Props): Unit = load(s, props)

def load(s: State, props: Props): Unit = Api.targets(props.formId).foreach(s.targets := _)

def dest(t: FormTargetDto): String = t.url.orElse(t.address).getOrElse("")
def events(t: FormTargetDto): String = t.events.map(FormEvent.label).mkString("・")

def edit(s: State, t: Option[FormTargetDto]): Unit = {
  s.editId := t.map(_.id)
  s.name := t.fold("")(_.name); s.kind := t.fold(TargetKind.ChatHook)(_.kind)
  s.url := t.flatMap(_.url).getOrElse(""); s.address := t.flatMap(_.address).getOrElse("")
  val ev = t.fold(List(FormEvent.ResponseCreated))(_.events)
  s.onResponse := ev.contains(FormEvent.ResponseCreated); s.onOpen := ev.contains(FormEvent.Opened); s.onClose := ev.contains(FormEvent.Closed)
  s.enabled := t.forall(_.enabled)
  s.open := true
}

def request(s: State): FormTargetReq = {
  val ev = List(FormEvent.ResponseCreated -> s.onResponse.v, FormEvent.Opened -> s.onOpen.v, FormEvent.Closed -> s.onClose.v).collect { case (e, true) => e }
  FormTargetReq(s.name.v, s.kind.v, Option(s.url.v.trim).filter(_.nonEmpty), Option(s.address.v.trim).filter(_.nonEmpty), ev, s.enabled.v)
}

def isNew(s: State) = s.editId.mapC(_.isEmpty)
def isMail(s: State) = s.kind.mapC(_ == TargetKind.Mail)
def isChat(s: State) = s.kind.mapC(_ == TargetKind.ChatHook)
def isWebhook(s: State) = s.kind.mapC(_ == TargetKind.Webhook)
def kindLabel(s: State) = s.kind.mapC(TargetKind.label)
/** 編集中の Webhook の署名の鍵（作った後だけ表示する）. */
def secret(s: State) = computed { s.editId.v.flatMap(id => s.targets.v.find(_.id == id)).flatMap(_.secret).getOrElse("") }

val kindOpts: List[Opt] = TargetKind.all.map(k => Opt(k, TargetKind.label(k)))

def save(s: State, props: Props): Unit = {
  val f = s.editId.v match {
    case Some(x) => Api.updateTarget(props.formId, x, request(s))
    case None    => Api.createTarget(props.formId, request(s))
  }
  Toasts.report(f) { t =>
    load(s, props)
    // 作った Webhook は、署名の鍵を見せるために開いたままにする
    if (s.editId.v.isEmpty && t.kind == TargetKind.Webhook) s.editId := Some(t.id) else s.open := false
    Toasts.show("通知先を保存しました")
  }
}

def remove(s: State, props: Props, t: FormTargetDto): Unit =
  Dialog.confirm(s"通知先「${t.name}」を削除しますか？", "削除") {
    Toasts.report(Api.deleteTarget(props.formId, t.id))(_ => load(s, props))
  }

def test(s: State, props: Props, t: FormTargetDto): Unit = {
  s.testing := Some(t.id)
  val f = Api.testTarget(props.formId, t.id)
  f.onComplete(_ => s.testing := None)
  Toasts.report(f) { _ => Toasts.show(s"「${t.name}」に試しの通知を送りました"); load(s, props) }
}
def testingId(s: State) = s.testing.mapC(_.map(_.toString).getOrElse(""))
def hasMail = Store.config.mapC(_.exists(_.mail))
def stuiName = AppNames.of("stui")
</script>

<template>
  <section class="panel">
    <div class="row"><h2 class="grow">通知先（Webhook・メールアドレス）</h2><button class="primary small" @click="edit(s, None)">＋ 通知先を追加</button></div>
    <p class="muted small">回答が届いた・受付を始めた・締め切ったときに、ほかのサービスや決まったメールアドレスへ知らせます。回答の中身がフォームの外へ出るので、持ち主と管理者だけが設定できます。</p>
    <table class="list">
      <thead><tr><th>名前</th><th>送り先</th><th>きっかけ</th><th></th></tr></thead>
      <tbody>
        <tr v-for="t in s.targets" :key="t.id">
          <td><b>{{ t.name }}</b> <span v-if="!t.enabled" class="badge">無効</span>
            <div class="muted small">{{ TargetKind.label(t.kind) }}</div>
            <div v-if="t.lastError.isDefined" class="err small">送れませんでした: {{ t.lastError.getOrElse("") }}</div></td>
          <td class="small dest">{{ dest(t) }}</td>
          <td class="small">{{ events(t) }}</td>
          <td class="nowrap">
            <button class="small" :disabled="testingId(s).mapC(_ == t.id.toString)" @click="test(s, props, t)">試す</button>
            <button class="small" @click="edit(s, Some(t))">編集</button>
            <button class="danger small" @click="remove(s, props, t)">削除</button>
          </td>
        </tr>
        <tr v-if="s.targets.mapC(_.isEmpty)"><td colspan="4" class="muted">通知先はありません</td></tr>
      </tbody>
    </table>

    <Modal v-if="s.open" :onClose="_ => s.open := false" :wide="true">
      <h2>通知先</h2>
      <label class="field"><span>名前</span><input type="text" v-model="s.name" placeholder="例: 総務チャンネル" maxlength="100"></label>
      <div class="field"><span>種類</span>
        <Combo v-if="isNew(s)" :bind="Some(s.kind)" :options="kindOpts" />
        <span v-if="isNew(s).mapC(!_)">{{ kindLabel(s) }}</span></div>

      <div v-if="isMail(s)">
        <label class="field"><span>メールアドレス</span><input type="email" v-model="s.address" placeholder="desk@example.com" maxlength="254"></label>
        <p v-if="hasMail.mapC(!_)" class="err small">このサーバーではメールを送れません（管理者が SMTP を設定すると使えます）。</p>
        <p class="muted small">{{ AppNames.self }} を使っていない人（問い合わせの窓口など）にも送れます。回答の要約と、回答の一覧へのリンクを送ります。</p>
      </div>

      <div v-if="isMail(s).mapC(!_)">
        <label class="field"><span>送り先の URL</span><input type="url" v-model="s.url" placeholder="https://…"></label>
        <p v-if="isChat(s)" class="muted small">{{ stuiName }}・Slack・Mattermost の受信 Webhook の URL です（{{ stuiName }} ではチャンネルの設定の「連携」で作れます）。
          見出しと回答の要約を <code>{"text": "…", "username": "smohua"}</code> で投稿します。チャンネルの全員に回答の要約が見えます。</p>
        <div v-if="isWebhook(s)">
          <p class="muted small">イベント（<code>response.created</code>・<code>form.opened</code>・<code>form.closed</code>）の JSON を POST します。回答はすべての質問の答えを含みます。
            ヘッダー <code>X-Smohua-Event</code> はイベントの名前、<code>X-Smohua-Delivery</code> は送信の ID（送り直しても同じ）、<code>X-Smohua-Signature</code> は本文の HMAC-SHA256（<code>sha256=…</code>）です。下の署名の鍵で確かめてください。</p>
          <div v-if="secret(s).mapC(_.nonEmpty)" class="field"><span>署名の鍵</span>
            <div class="row"><code class="keytext grow">{{ secret(s) }}</code><button type="button" class="small" @click='Dom.copy(secret(s).v, "署名の鍵をコピーしました")'>コピー</button></div></div>
          <p v-if="isNew(s)" class="muted small">署名の鍵は、保存すると作ります。</p>
        </div>
      </div>

      <div class="field"><span>知らせるきっかけ</span>
        <label class="check"><input type="checkbox" v-model="s.onResponse"> 回答が届いた</label>
        <label class="check"><input type="checkbox" v-model="s.onOpen"> 受付を始めた（受付期間の始まりを含む）</label>
        <label class="check"><input type="checkbox" v-model="s.onClose"> 締め切った（受付期間の終わりを含む）</label></div>
      <label class="check"><input type="checkbox" v-model="s.enabled"> 有効</label>
      <div class="actions">
        <button @click="s.open := false">閉じる</button>
        <button class="primary" @click="save(s, props)">保存</button>
      </div>
    </Modal>
  </section>
</template>

<style scoped>
.panel h2 { margin-top: 0; }
.dest { word-break: break-all; }
.nowrap { white-space: nowrap; }
.err { color: var(--danger); }
.keytext { background: var(--side); padding: .3rem .5rem; border-radius: 5px; word-break: break-all; font-size: .8rem; }
.list { width: 100%; }
</style>
