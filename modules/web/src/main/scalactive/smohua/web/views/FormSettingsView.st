<script lang="scala">
import smohua.web.*
import smohua.web.parts.*
import smohua.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

import org.scalajs.dom

// フォームの設定: だれが回答できるか・受付期間・回答の画面の見た目（埋め込み用）・回答した後の文・回答が届いたときの知らせ方・編集できる人・複製と削除
case class Props(formId: UUID)

case class State(form: RV[Option[FormDto]] = RV(None), access: RV[String] = RV(FormAccess.Login), once: RV[Boolean] = RV(false),
                 confirmation: RV[String] = RV(""), inApp: RV[Boolean] = RV(true), mail: RV[Boolean] = RV(false), stui: RV[Boolean] = RV(false),
                 stuiChannel: RV[String] = RV(""), editors: RV[List[UserBrief]] = RV(Nil), userQ: RV[String] = RV(""),
                 found: RV[List[UserBrief]] = RV(Nil), stuiLinked: RV[Boolean] = RV(false),
                 from: RV[String] = RV(""), until: RV[String] = RV(""),
                 bg: RV[String] = RV(""), card: RV[String] = RV(""), text: RV[String] = RV(""), accent: RV[String] = RV(""),
                 submitLabel: RV[String] = RV(""), showTitle: RV[Boolean] = RV(true), showDesc: RV[Boolean] = RV(true),
                 showBrand: RV[Boolean] = RV(true), showAnon: RV[Boolean] = RV(true), css: RV[String] = RV(""), embedH: RV[String] = RV("800"),
                 saved: RV[String] = RV(""), unguard: RV[Option[() => Unit]] = RV(None))

def initState() = State()

def show(s: State, f: FormDto): Unit = {
  s.form := Some(f); s.access := f.access; s.once := f.oneResponse; s.confirmation := f.confirmation
  s.inApp := f.notice.inApp; s.mail := f.notice.mail; s.stui := f.notice.stui; s.stuiChannel := f.notice.stuiChannel
  s.editors := f.editors
  s.from := f.schedule.from.map(Fmt.localInput).getOrElse(""); s.until := f.schedule.until.map(Fmt.localInput).getOrElse("")
  val a = f.appearance
  s.bg := a.background; s.card := a.card; s.text := a.text; s.accent := a.accent; s.submitLabel := a.submitLabel
  s.showTitle := a.showTitle; s.showDesc := a.showDescription; s.showBrand := a.showBranding; s.showAnon := a.showAnonymous; s.css := a.css
  s.saved := request(s).toString
  pageTitle(f.title + " の設定")
}

/** 保存していない変更があるか. */
def dirty(s: State): Boolean = s.form.v.exists(_.canEdit) && request(s).toString != s.saved.v

def mounted(s: State, props: Props): Unit = {
  Store.report(Api.form(props.formId))(show(s, _))
  Api.links().foreach(ls => s.stuiLinked := ls.exists(l => l.provider == "stui" && l.connected))
  // 保存せずにほかの画面へ移る・ページを閉じるときは確かめる
  s.unguard := Some(LeaveGuard.install(() => dirty(s)))
}

def unmounted(s: State): Unit = { s.unguard.v.foreach(_()); s.unguard := None }

/** 保存する内容（変更があるかの比べ合わせにも使う）. */
def request(s: State): UpdateFormReq =
  UpdateFormReq(access = Some(s.access.v), oneResponse = Some(s.once.v && s.access.v == FormAccess.Login),
    confirmation = Some(s.confirmation.v), notice = Some(FormNotifyDto(s.inApp.v, s.mail.v, s.stui.v, s.stuiChannel.v)),
    editorIds = Option.when(ownerOrAdmin(s))(s.editors.v.map(_.id)),
    schedule = Some(FormScheduleDto(Fmt.fromLocalInput(s.from.v), Fmt.fromLocalInput(s.until.v))),
    appearance = Some(FormAppearanceDto(s.bg.v, s.card.v, s.text.v, s.accent.v, s.submitLabel.v, s.showTitle.v, s.showDesc.v, s.showBrand.v,
      s.showAnon.v, s.css.v)))

def save(s: State, props: Props): Unit =
  Store.report(Api.updateForm(props.formId, request(s))) { f => show(s, f); Store.toast("保存しました") }

def search(s: State): Unit = {
  val q = s.userQ.v.trim
  if (q.isEmpty) s.found := Nil
  // 打つたびに探すので、届いた順が前後しても、いまの入力に対する結果だけを出す
  else Api.users(q, 8).foreach(us => if (s.userQ.v.trim == q)
    s.found := us.filterNot(u => s.editors.v.exists(_.id == u.id) || s.form.v.exists(_.owner.id == u.id)))
}

def addEditor(s: State, u: UserBrief): Unit = { s.editors := s.editors.v :+ u; s.found := Nil; s.userQ := "" }
def removeEditor(s: State, u: UserBrief): Unit = s.editors := s.editors.v.filterNot(_.id == u.id)

def duplicate(props: Props): Unit =
  Store.report(Api.duplicateForm(props.formId)) { f => Store.toast("フォームを複製しました（下書き）"); Router.go(Paths.form(f.id)) }

def remove(s: State, props: Props): Unit =
  Dialog.confirm(s"フォーム「${s.form.v.map(_.title).getOrElse("")}」と、集めた回答をすべて削除しますか？", "削除する") {
    Store.report(Api.deleteForm(props.formId)) { _ =>
      Store.toast("フォームを削除しました")
      s.unguard.v.foreach(_()); s.unguard := None // 消したフォームの変更は確かめない
      Router.go("/")
    }
  }

/** 埋め込み用の iframe の HTML. */
def embedCode(s: State, props: Props) = s.embedH.mapC { h =>
  val height = h.trim.toIntOption.filter(_ > 0).getOrElse(800)
  s"""<iframe src="${dom.window.location.origin}${Paths.fill(props.formId)}?embed=1" width="100%" height="$height" style="border:0" title="${Html.escape(s.form.v.map(_.title).getOrElse(""))}"></iframe>"""
}

def forms(s: State) = s.form.mapC(_.toList)
def accesses: List[(String, String)] = FormAccess.all.map(a => a -> FormAccess.label(a))
def isLogin(s: State) = s.access.mapC(_ == FormAccess.Login)
/** 持ち主か管理者か. 編集できる人と stui のチャンネル（持ち主の連携で投稿するため）を変えられるのは、持ち主と管理者だけ. */
def ownerOrAdmin(s: State): Boolean = s.form.v.exists(f => Store.myId.contains(f.owner.id)) || Store.me.v.exists(_.isAdmin)
def owner(s: State) = computed { ownerOrAdmin(s) }
def notOwner(s: State) = computed { !ownerOrAdmin(s) }
def hasMail = Store.config.mapC(_.exists(_.mail))
def hasStui = Store.config.mapC(_.exists(_.stui))
def needLink(s: State) = computed { s.stui.v && !s.stuiLinked.v }
</script>

<template>
  <div class="page narrow">
    <FormHeader v-for="f in forms(s)" :form="f" tab="settings" :onChanged="g => s.form := Some(g)" />
    <div v-if="s.form.mapC(_.isDefined)">
      <section class="panel">
        <h2>回答できる人</h2>
        <label v-for="a in accesses" class="check"><input type="radio" name="access" :value="a._1" v-model="s.access"> {{ a._2 }}</label>
        <p class="muted small">「URL を知っている人」のフォームは匿名です（ログインしている人が答えても、だれの回答かは記録しません）。</p>
        <label v-if="isLogin(s)" class="check"><input type="checkbox" v-model="s.once"> 1人1回だけ回答できる</label>
        <label class="field conf"><span>回答した後に見せる文</span><textarea v-model="s.confirmation" maxlength="2000" placeholder="回答を受け付けました。ありがとうございました。"></textarea></label>
      </section>

      <section class="panel">
        <h2>受付期間</h2>
        <p class="muted small">「受付中」のフォームは、この期間だけ回答を受け付けます（空欄は期限なし）。期間の外では、回答の画面に「受付前」「締め切り」と出ます。</p>
        <div class="row wrap">
          <label class="field"><span>受付の始まり</span><input type="datetime-local" v-model="s.from"></label>
          <label class="field"><span>受付の終わり</span><input type="datetime-local" v-model="s.until"></label>
          <button class="small ghost clear" @click='s.from := ""; s.until := ""'>期間を消す</button>
        </div>
      </section>

      <section class="panel">
        <h2>回答の画面の見た目</h2>
        <p class="muted small">ほかのサイトに埋め込むときに、サイトに合わせて変えられます。色は空なら {{ AppNames.self }} の既定の色（明るい・暗いテーマに合わせる）です。</p>
        <ColorField label="背景色" :value="s.bg" />
        <ColorField label="カードの色" :value="s.card" />
        <ColorField label="文字色" :value="s.text" />
        <ColorField label="強調色（ボタンなど）" :value="s.accent" />
        <label class="field narrowf"><span>送信ボタンの文言</span><input type="text" v-model="s.submitLabel" placeholder="送信" maxlength="30"></label>
        <div class="toggles">
          <label class="check"><input type="checkbox" v-model="s.showTitle"> 題名を出す</label>
          <label class="check"><input type="checkbox" v-model="s.showDesc"> 説明を出す</label>
          <label class="check"><input type="checkbox" v-model="s.showBrand"> 「{{ AppNames.self }} のフォーム」を出す</label>
          <label class="check"><input type="checkbox" v-model="s.showAnon"> 「この回答は匿名で送られます」を出す</label>
        </div>
        <details class="adv">
          <summary>高度な設定（CSS）</summary>
          <p class="muted small">回答の画面にだけ足す CSS です。例: <code>.fill .card { border-radius: 0; }</code>。外のサイトの読み込み（@import、http の url()）は使えません。</p>
          <textarea v-model="s.css" rows="8" class="css" spellcheck="false" placeholder=".fill h1 { font-size: 1.4rem; }"></textarea>
        </details>
        <h3>埋め込み</h3>
        <p class="muted small">この HTML をほかのサイトに貼ると、フォームが埋め込まれます（上のヘッダーは出ません）。埋め込んだ画面ではログインできないことが多いので、「URL を知っている人」のフォームで使ってください。</p>
        <div class="row">
          <label class="field hf"><span>高さ（px）</span><input type="number" min="200" v-model="s.embedH"></label>
        </div>
        <div class="row embed"><code class="grow">{{ embedCode(s, props) }}</code>
          <button class="small" @click='Dom.copy(embedCode(s, props).v, "埋め込み用の HTML をコピーしました")'>コピー</button></div>
        <p class="muted small">見た目は保存すると反映されます。<a :href='Paths.fill(props.formId) + "?embed=1"' target="_blank" rel="noopener">埋め込んだときの見た目を開く</a></p>
      </section>

      <section class="panel">
        <h2>回答が届いたときの知らせ</h2>
        <p class="muted small">持ち主と、編集できる人に知らせます。</p>
        <label class="check"><input type="checkbox" v-model="s.inApp"> {{ AppNames.self }} の通知（🔔）とプッシュ通知</label>
        <label v-if="hasMail" class="check"><input type="checkbox" v-model="s.mail"> メール</label>
        <label v-if="hasStui" class="check"><input type="checkbox" v-model="s.stui"> {{ AppNames.of("stui") }}（チャット）</label>
        <div v-if="s.stui" class="sub">
          <label class="field"><span>投稿するチャンネル（空なら、連携している人それぞれに DM で知らせます）</span>
            <input type="text" v-model="s.stuiChannel" placeholder="例: 総務" maxlength="100" :disabled="notOwner(s)"></label>
          <p v-if="needLink(s)" class="small warn">{{ AppNames.of("stui") }} と連携していません。<a href="/settings" @click.prevent='Router.go("/settings")'>個人設定</a>の「{{ AppNames.of("stui") }} 連携」で連携すると届きます。</p>
          <p class="muted small">チャンネルへの投稿は、持ち主の {{ AppNames.of("stui") }} の連携を使います（持ち主がそのチャンネルに参加している必要があります）。チャンネルを変えられるのは、持ち主と管理者だけです。</p>
        </div>
      </section>

      <section class="panel">
        <h2>編集できる人</h2>
        <p class="muted small">質問と設定を変えられ、回答を見られる人です。</p>
        <div v-for="f in forms(s)" class="row person"><Avatar :user="Some(f.owner)" :size="24" /><span class="grow">{{ f.owner.name }}</span><span class="badge">持ち主</span></div>
        <div v-for="u in s.editors" :key="u.id" class="row person">
          <Avatar :user="Some(u)" :size="24" /><span class="grow">{{ u.name }}</span>
          <button v-if="owner(s)" class="small ghost" @click="removeEditor(s, u)">外す</button>
        </div>
        <div v-if="owner(s)" class="add">
          <input type="search" v-model="s.userQ" placeholder="名前・メールで探して追加" @input="search(s)">
          <div v-if="s.found.mapC(_.nonEmpty)" class="found">
            <a v-for="u in s.found" :key="u.id" href="#" class="row" @click.prevent="addEditor(s, u)"><Avatar :user="Some(u)" :size="22" /> {{ u.name }}</a>
          </div>
        </div>
      </section>

      <div class="row bar"><span class="grow"></span><button class="primary" @click="save(s, props)">保存</button></div>

      <section class="panel">
        <h2>複製・削除</h2>
        <div class="row">
          <button @click="duplicate(props)">このフォームを複製する</button>
          <span class="grow"></span>
          <button v-if="owner(s)" class="danger" @click="remove(s, props)">フォームを削除する</button>
        </div>
        <p class="muted small">複製は、質問と設定だけを写した新しい下書きを作ります（回答は写しません）。</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.panel h2 { margin-top: 0; }
.conf { margin-top: .9rem; }
.sub { margin: .3rem 0 0 1.5rem; }
.warn { color: var(--warn); }
.person { padding: .3rem 0; }
.add { position: relative; margin-top: .5rem; max-width: 360px; }
.add input { width: 100%; }
.found { position: absolute; left: 0; right: 0; top: 2.3rem; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; box-shadow: var(--shadow); z-index: 10; padding: .2rem; }
.found a { padding: .35rem .5rem; border-radius: 6px; color: var(--fg); }
.found a:hover { background: var(--hover); text-decoration: none; }
.bar { margin-bottom: 1rem; }
.wrap { flex-wrap: wrap; align-items: flex-end; gap: .8rem; }
.clear { margin-bottom: .2rem; }
.narrowf { max-width: 280px; margin-top: .6rem; }
.toggles { display: flex; flex-direction: column; margin: .6rem 0; }
.adv { margin: .4rem 0 1rem; }
.adv summary { cursor: pointer; font-weight: 600; }
.css { width: 100%; font-family: monospace; font-size: .85rem; }
.hf input { width: 7rem; }
.embed { background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .4rem .6rem; }
.embed code { font-size: .78rem; overflow-wrap: anywhere; }
</style>
