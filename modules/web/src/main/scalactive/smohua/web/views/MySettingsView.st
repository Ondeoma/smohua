<script lang="scala">
import smohua.web.*
import smohua.web.parts.*
import io.circe.syntax.*
import org.scalajs.dom
import smohua.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 個人設定: 画面の色・通知（デスクトップ・プッシュ）・stui との連携・APIキー（MCP）
case class State(keys: RV[List[ApiKeyDto]] = RV(Nil),
                 keyName: RV[String] = RV(""),
                 scopes: RV[List[String]] = RV(List("read", "write")),
                 desktop: RV[Boolean] = RV(Prefs.get("desktop", "on") == "on"),
                 days: RV[String] = RV("90"),
                 created: RV[Option[String]] = RV(None),
                 error: RV[String] = RV(""),
                 links: RV[List[LinkServiceDto]] = RV(Nil))

def initState() = State()

def mounted(s: State): Unit = {
  pageTitle("個人設定")
  loadKeys(s)
  loadLinks(s)
  // 連携の許可画面から戻ったとき（?integration=stui&result=connected）
  val q = new dom.URL(dom.window.location.href).searchParams
  Option(q.get("integration")).foreach { p =>
    q.get("result") match {
      case "connected" => Store.toast(s"${AppNames.nameOf(p)} と連携しました")
      case "denied"    => Store.toast(s"${AppNames.nameOf(p)} との連携は許可されませんでした", error = true)
      case _           => Store.toast(s"${AppNames.nameOf(p)} と連携できませんでした。もう一度お試しください", error = true)
    }
    dom.window.history.replaceState(null, "", "/settings")
  }
}

def loadLinks(s: State): Unit = Api.links().foreach(s.links := _)

def connect(p: String): Unit =
  dom.window.location.href = s"/api/integrations/$p/connect?returnTo=" + Api.enc(dom.window.location.origin + "/settings")

def disconnect(s: State, l: LinkServiceDto): Unit = Dialog.confirm(s"${l.name} との連携をやめますか？（回答が届いたときの知らせが ${l.name} に出なくなります）", "やめる") {
  Store.report(Api.delete(s"/api/integrations/${l.provider}/connection")) { _ => Store.toast(s"${l.name} との連携をやめました"); loadLinks(s) }
}

def setDesktop(s: State, on: Boolean): Unit = {
  s.desktop := on
  Prefs.set("desktop", if (on) "on" else "off")
  if (on) Store.askDesktopPermission()
}

def meBrief = Store.me.mapC(_.map(m => UserBrief(m.id, m.name, m.avatarUrl)))
def myName = Store.me.mapC(_.map(_.name).getOrElse(""))
def myEmail = Store.me.mapC(_.flatMap(_.email).getOrElse(""))

def loadKeys(s: State): Unit = Api.get[List[ApiKeyDto]]("/api/me/api-keys").foreach(s.keys := _)

def createKey(s: State): Unit = {
  s.error := ""
  Api.post[CreatedApiKeyDto]("/api/me/api-keys", CreateApiKeyReq(s.keyName.v, s.scopes.v, s.days.v.toIntOption).asJson).onComplete {
    case Success(r) => s.created := Some(r.key); s.keyName := ""; loadKeys(s)
    case Failure(e) => s.error := e.getMessage
  }
}

def revoke(s: State, k: ApiKeyDto): Unit =
  Dialog.confirm(s"APIキー「${k.name}」を失効させますか？使っているプログラム・AI は使えなくなります。") {
    Store.report(Api.delete(s"/api/me/api-keys/${k.id}"))(_ => loadKeys(s))
  }


def origin: String = dom.window.location.origin
def mcpCommand(s: State) = s.created.mapC(k => s"claude mcp add --transport http smohua $origin/mcp --header \"Authorization: Bearer ${k.getOrElse("<APIキー>")}\"")
def via(k: ApiKeyDto): String = k.client.fold("")(c => s"（$c 連携）")
</script>

<template>
  <div class="page narrow">
    <h1>個人設定</h1>
    <section class="panel">
      <h2>プロフィール</h2>
      <div class="row avatar-row">
        <Avatar :user="meBrief.v" :size="64" />
        <div>
          <div><b>{{ myName }}</b></div>
          <div class="muted small">{{ myEmail }}</div>
        </div>
      </div>
      <p class="muted small">名前とアイコンは、ログインに使うサービス（{{ AppNames.of("sruru") }}）で登録したものです。{{ AppNames.of("sruru") }} で変えると、次にログインしたときに反映されます（アイコンを外したときも同じです）。</p>
    </section>

    <section class="panel">
      <h2>画面の表示</h2>
      <div class="field"><span>画面の色（この端末に記憶します）</span>
        <div class="chips">
          <button v-for="o in Theme.options" :class='Theme.current.mapC(c => if (c == o._1) "chip active" else "chip")' @click="Theme.set(o._1)">{{ o._2 }}</button>
        </div>
      </div>
    </section>

    <section class="panel">
      <h2>通知</h2>
      <label class="check"><input type="checkbox" :checked="s.desktop.v" @change="setDesktop(s, Dom.checked(event))"> 画面を開いていないとき、デスクトップに通知を出す（この端末）</label>
      <p><PushToggle /></p>
      <p class="muted small">回答が届いたときに知らせるかどうか（{{ AppNames.self }} の通知・メール・{{ AppNames.of("stui") }}）は、フォームごとに「設定」で選びます。</p>
    </section>

    <section v-for="l in s.links" class="panel">
      <h2>{{ l.name }} 連携</h2>
      <p class="muted small">フォームに回答が届いたとき、{{ AppNames.of("stui") }}（チャット）のあなた宛ての DM、またはフォームの設定で選んだチャンネルに知らせます（<code>{{ l.url }}</code>）。</p>
      <div class="row">
        <span v-if="l.connected" class="badge cat-done">連携しています</span>
        <span v-else class="badge">連携していません</span>
        <span class="grow"></span>
        <button v-if="l.connected" @click="disconnect(s, l)">連携をやめる</button>
        <button v-else class="primary" @click="connect(l.provider)">{{ l.name }} と連携する</button>
      </div>
    </section>

    <section class="panel">
      <h2>APIキー（REST API・MCP）</h2>
      <p class="muted small">プログラムや AI（MCP）から {{ AppNames.self }} を使うためのキーです。<code>Authorization: Bearer &lt;キー&gt;</code> で送ります。
        権限は「あなたの権限」と「キーのスコープ」の両方を満たす範囲です。</p>
      <form @submit.prevent="createKey(s)">
        <div class="row">
          <input type="text" v-model="s.keyName" placeholder="用途（例: Claude Code）" maxlength="100" class="grow">
          <select v-model="s.days"><option value="30">30日</option><option value="90">90日</option><option value="365">1年</option><option value="">無期限</option></select>
        </div>
        <div class="row scopes">
          <span class="small muted">スコープ:</span>
          <label class="small"><input type="checkbox" value="read" v-model="s.scopes"> 参照（read）</label>
          <label class="small"><input type="checkbox" value="write" v-model="s.scopes"> 作成・更新・削除（write）</label>
          <label v-if="Store.isAdmin" class="small"><input type="checkbox" value="admin" v-model="s.scopes"> 管理（admin）</label>
        </div>
        <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
        <button type="submit" class="primary">発行</button>
      </form>
      <div v-if="s.created.mapC(_.isDefined)" class="created">
        <p><b>APIキーを発行しました。</b>この画面を離れると二度と表示できません。安全な場所に保存してください。</p>
        <div class="row"><code class="grow keytext">{{ s.created.mapC(_.getOrElse("")) }}</code><button class="small" @click="Dom.copy(s.created.v.getOrElse(&quot;&quot;))">コピー</button></div>
        <p class="small">Claude Code から使う（MCP）:</p>
        <div class="row"><code class="grow keytext">{{ mcpCommand(s) }}</code><button class="small" @click="Dom.copy(mcpCommand(s).v)">コピー</button></div>
        <button class="small" @click="s.created := None">閉じる</button>
      </div>
      <div class="table-wrap keys">
        <table class="list">
          <thead><tr><th>用途</th><th>キー</th><th>スコープ</th><th>有効期限</th><th>最終使用</th><th></th></tr></thead>
          <tbody>
            <tr v-for="k in s.keys" :key="k.id">
              <td>{{ k.name }} <span class="muted small">{{ via(k) }}</span></td><td><code>{{ k.prefix }}…</code></td><td>{{ k.scopes.mkString(", ") }}</td>
              <td class="small">{{ k.expiresAt.map(Fmt.dateTime).getOrElse("無期限") }}</td>
              <td class="small">{{ k.lastUsedAt.map(Fmt.ago).getOrElse("未使用") }}</td>
              <td><button class="danger small" @click="revoke(s, k)">失効</button></td>
            </tr>
            <tr v-if="s.keys.mapC(_.isEmpty)"><td colspan="6" class="muted">発行済みの APIキーはありません</td></tr>
          </tbody>
        </table>
      </div>
      <p class="small muted">MCP のエンドポイント: <code>{{ origin }}/mcp</code>（Streamable HTTP）。REST API の一覧は <a href="/api/endpoints" target="_blank">/api/endpoints</a>。</p>
    </section>
  </div>
</template>

<style scoped>
.avatar-row { margin-bottom: .8rem; gap: 1rem; }
.scopes { margin: .5rem 0; }
.created { border: 1px solid var(--ok); border-radius: 8px; padding: .8rem 1rem; margin: 1rem 0; background: color-mix(in srgb, var(--ok) 8%, transparent); }
.keytext { background: var(--side); padding: .3rem .5rem; border-radius: 5px; word-break: break-all; font-size: .8rem; }
.keys { margin-top: 1rem; }
</style>
