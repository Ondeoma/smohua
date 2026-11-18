<script lang="scala">
import io.github.ondeoma.scalactive.components.routes.RouterComponent
import org.scalajs.dom
import smohua.shared.*
import smohua.web.parts.*
import smohua.web.views.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 画面全体の枠: 上のヘッダー（フォームの一覧・通知・メニュー）と、ルートごとの画面
case class State(ready: RV[Boolean] = RV(false), menu: RV[Boolean] = RV(false))

def initState() = State()

val routes: PathPatternRoutes = List(
  ("/", _ => FormListView()),
  ("/login", _ => LoginView()),
  ("/forms/:id", r => FormEditView(formId = UUID.fromString(r.pathname.groups("id")))),
  ("/forms/:id/responses", r => ResponsesView(formId = UUID.fromString(r.pathname.groups("id")))),
  ("/forms/:id/settings", r => FormSettingsView(formId = UUID.fromString(r.pathname.groups("id")))),
  ("/f/:id", r => FillView(formId = UUID.fromString(r.pathname.groups("id")))),
  ("/activity", _ => NotificationView()),
  ("/settings", _ => MySettingsView()),
  ("/admin", _ => AdminView()),
)

def mounted(s: State): Unit = {
  Fallback.install(routes)
  Api.clientConfig().foreach(c => Store.config := Some(c))
  if (dom.window.location.pathname.startsWith("/login")) s.ready := true
  else Api.me().onComplete {
    case Success(me) =>
      Store.me := Some(me)
      Observe.setUser(me.id.toString)
      Store.refreshUnread()
      Store.connect()
      s.ready := true
    // 回答の画面（/f/…）は、ログインしていなくても開ける（ほかの画面は Api がログインへ送る）
    case Failure(_) => if (dom.window.location.pathname.startsWith("/f/")) s.ready := true
  }
}

def logout(): Unit = Api.logout().onComplete(r => dom.window.location.href = r.getOrElse("/"))

def userName = Store.me.mapC(_.map(_.name).getOrElse(""))
def meBrief = Store.me.mapC(_.map(m => UserBrief(m.id, m.name, m.avatarUrl)))
def unreadLabel = Store.unread.mapC(n => if (n > 99) "99+" else n.toString)
def hasUnread = Store.unread.mapC(_ > 0)
/** iframe で埋め込んだ回答の画面（?embed=1）では、ヘッダーを出さない. */
def showHeader = Store.me.mapC(_.isDefined && !FormLook.embedded)
</script>

<template>
  <div class="shell">
    <header v-if="showHeader" class="top">
      <RouterLink to="/" class="brand"><img src="/logo.svg" alt="" width="28" height="28"><span>smohua</span></RouterLink>
      <RouterLink to="/" class="nav hide-sp">フォーム</RouterLink>
      <span class="grow"></span>
      <RouterLink to="/activity" class="icon bell">
        🔔<span v-if="hasUnread" class="dot">{{ unreadLabel }}</span>
      </RouterLink>
      <div class="me">
        <button class="ghost me-btn" @click="s.menu := !s.menu.v"><Avatar :user="meBrief.v" :size="26" /><span class="hide-sp">{{ userName }}</span> ▾</button>
        <div v-if="s.menu" class="menu-bg" @click="s.menu := false"></div>
        <div v-if="s.menu" class="menu" @click="s.menu := false">
          <RouterLink to="/settings">個人設定・連携・APIキー</RouterLink>
          <RouterLink v-if="Store.isAdmin" to="/admin">管理</RouterLink>
          <a href="#" @click.prevent="logout()">ログアウト</a>
        </div>
      </div>
    </header>
    <main class="content">
      <RouterComponent v-if="s.ready" :routes="routes" />
    </main>
  </div>
  <DialogHost />
  <div class="toasts">
    <div v-for="t in Store.toasts" :key="t.id" :class='if (t.error) "toast error" else "toast"'>
      <span class="grow" @click="t.link.foreach(l => Router.go(l)); Store.dismiss(t.id)">{{ t.text }}</span>
      <button @click="Store.dismiss(t.id)">×</button>
    </div>
  </div>
</template>

<style scoped>
.shell { min-height: 100vh; min-height: 100dvh; display: flex; flex-direction: column; }
.content { flex: 1; min-width: 0; }
.top { height: var(--header); flex: none; display: flex; align-items: center; gap: .8rem; padding: 0 1rem; background: var(--bg);
  border-bottom: 1px solid var(--line); position: sticky; top: 0; z-index: 100; }
.me { position: relative; }
.menu { position: absolute; right: 0; top: 2.4rem; background: var(--bg); border: 1px solid var(--line); border-radius: 9px; box-shadow: var(--shadow);
  min-width: 230px; padding: .3rem; display: flex; flex-direction: column; z-index: 200; }
.menu-bg { position: fixed; inset: 0; z-index: 199; }
@media (max-width: 720px) { .top { padding: 0 .6rem; gap: .4rem; } }
</style>
