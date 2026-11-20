<script lang="scala">
import smohua.web.*
import io.circe.syntax.*
import smohua.shared.*
import smohua.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 管理（システム管理者）: ユーザー・監査ログ（すべての人のフォームは、フォームの一覧の「すべての人のフォーム」で見られる）
case class State(tab: RV[String] = RV("users"), users: RV[List[UserDto]] = RV(Nil), q: RV[String] = RV(""), audit: RV[List[AuditDto]] = RV(Nil))

def initState() = State()

def loadUsers(s: State): Unit = Toasts.report(Api.get[List[UserDto]]("/api/admin/users" + Api.query("q" -> Some(s.q.v.trim))))(s.users := _)
def loadAudit(s: State): Unit = Toasts.report(Api.get[List[AuditDto]]("/api/admin/audit?limit=200"))(s.audit := _)

def mounted(s: State): Unit = { pageTitle("管理"); loadUsers(s); loadAudit(s) }

def openTab(s: State, v: String): Unit = { s.tab := v; if (v == "audit") loadAudit(s) }

/** 変えられなかったときも読み直す（チェックボックスを元の状態に戻す）. */
def setUser(s: State, u: UserDto, admin: Option[Boolean], disabled: Option[Boolean]): Unit =
  Api.patchUnit(s"/api/admin/users/${u.id}", UpdateUserReq(admin, disabled).asJson).onComplete { r =>
    r.failed.foreach(Toasts.error)
    loadUsers(s)
  }

/** 無効にするときは確かめる（確かめている間・やめたときは、チェックを入れない）. */
def setDisabled(s: State, u: UserDto, e: org.scalajs.dom.Event): Unit =
  if (!Dom.checked(e)) setUser(s, u, None, Some(false))
  else {
    e.preventDefault()
    Dialog.confirm(s"${u.name} を無効にしますか？（ログインしている画面からもログアウトされ、APIキーも使えなくなります）", "無効にする")(
      setUser(s, u, None, Some(true)))
  }

def tab(s: State, v: String) = s.tab.mapC(x => if (x == v) "active" else "")
def isTab(s: State, v: String) = s.tab.mapC(_ == v)
def me(u: UserDto): Boolean = Store.myId.contains(u.id)
</script>

<template>
  <div class="page">
    <h1>管理</h1>
    <nav class="tabs">
      <button :class='tab(s, "users")' @click='openTab(s, "users")'>ユーザー</button>
      <button :class='tab(s, "audit")' @click='openTab(s, "audit")'>監査ログ</button>
    </nav>
    <div v-if='isTab(s, "users")'>
      <form class="row" @submit.prevent="loadUsers(s)"><input type="search" v-model="s.q" placeholder="名前・メールで絞り込み"><button type="submit">検索</button></form>
      <p class="muted small">ユーザーは初めてログインしたときに作られます。名前とアイコンは、ログインのたびに IdP（{{ AppNames.of("sruru") }}）のものを写します。
        IdP のグループ（ADMIN_GROUPS）に入っている人は、ここでの設定によらず管理者です。</p>
      <div class="table-wrap">
        <table class="list">
          <thead><tr><th>名前</th><th>メール</th><th>グループ</th><th>最終ログイン</th><th>管理者</th><th>無効</th></tr></thead>
          <tbody>
            <tr v-for="u in s.users" :key="u.id">
              <td><Avatar :user="Some(Person(u.id.toString, u.name, u.avatarUrl))" :size="22" /> {{ u.name }}</td>
              <td class="small">{{ u.email.getOrElse("") }}</td>
              <td class="small">{{ u.groups.mkString(", ") }}</td>
              <td class="small">{{ u.lastLoginAt.map(Fmt.ago).getOrElse("") }}</td>
              <td><input type="checkbox" :checked="u.isAdmin" :disabled="me(u)" @change="setUser(s, u, Some(Dom.checked(event)), None)"></td>
              <td><input type="checkbox" :checked="u.disabled" :disabled="me(u)" @click="setDisabled(s, u, event)"></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-if='isTab(s, "audit")' class="table-wrap">
      <table class="list">
        <thead><tr><th>日時</th><th>操作者</th><th>経路</th><th>操作</th><th>対象</th><th>詳細</th></tr></thead>
        <tbody>
          <tr v-for="a in s.audit" :key="a.id">
            <td class="small nowrap">{{ Fmt.dateTime(a.at) }}</td>
            <td class="small">{{ a.actor.map(_.name).getOrElse("") }}</td>
            <td class="small">{{ a.via }}</td>
            <td><code class="small">{{ a.action }}</code></td>
            <td class="small key">{{ a.target.getOrElse("").take(8) }}</td>
            <td class="small muted">{{ a.detail.noSpaces.take(120) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
