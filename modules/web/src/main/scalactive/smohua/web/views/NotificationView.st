<script lang="scala">
import smohua.web.*
import smohua.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 通知（回答が届いた知らせ）. 押すと、そのフォームの回答を開く.
case class State(unread: RV[Boolean] = RV(false), list: RV[NotificationListDto] = RV(NotificationListDto(Nil, 0)), loading: RV[Boolean] = RV(true))

def initState() = State()

def load(s: State): Unit = Api.notifications(s.unread.v).foreach { l => s.list := l; s.loading := false }

def mounted(s: State): Unit = {
  pageTitle("通知")
  load(s)
  // 新しい通知が届いたときだけ読み直す（既読にして減ったときは、この画面の操作で読み直している）
  watch(Store.unread, false)((was, now) => if (now > was) load(s))
}

def open(n: NotificationDto): Unit = {
  if (!n.read) Api.markRead(List(n.id)).foreach(_ => Store.refreshUnread())
  Router.go(n.link)
}

def readAll(s: State): Unit = Store.report(Api.markRead(Nil)) { _ => load(s); Store.refreshUnread() }

def items(s: State) = s.list.mapC(_.items)
def empty(s: State) = computed { s.list.v.items.isEmpty && !s.loading.v }
</script>

<template>
  <div class="page narrow">
    <div class="row head">
      <h1 class="grow">🔔 通知</h1>
      <label class="check"><input type="checkbox" v-model="s.unread" @change="load(s)"> 未読だけ</label>
      <button class="small" @click="readAll(s)">すべて既読にする</button>
    </div>
    <p v-if="empty(s)" class="muted">通知はありません</p>
    <div v-for="n in items(s)" :key="n.id" :class='if (n.read) "n" else "n unread"' @click="open(n)">
      <span class="ic">📝</span>
      <div class="grow body">
        <div><b>{{ n.title }}</b> <span class="muted small">{{ Fmt.ago(n.at) }}</span></div>
        <div class="muted text">{{ n.body }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.head { align-items: center; gap: .8rem; }
.n { display: flex; gap: .6rem; align-items: flex-start; padding: .7rem .6rem; border-bottom: 1px solid var(--line); cursor: pointer; border-radius: 8px; }
.n:hover { background: var(--hover); }
.n.unread { background: var(--accent-weak); }
.ic { width: 1.4rem; text-align: center; }
.body { min-width: 0; }
.text { font-size: .9rem; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 6em; overflow: hidden; }
</style>
