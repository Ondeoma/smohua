<script lang="scala">
import smohua.web.*
import smohua.web.parts.*
import smohua.shared.*

// フォームの一覧（自分が持ち主か、編集できるもの）. ここから新しいフォームを作る.
case class State(forms: RV[List[FormSummaryDto]] = RV(Nil), q: RV[String] = RV(""), all: RV[Boolean] = RV(false), loading: RV[Boolean] = RV(true))

def initState() = State()

def load(s: State): Unit = Store.report(Api.forms(s.q.v.trim, s.all.v)) { fs => s.forms := fs; s.loading := false }

def mounted(s: State): Unit = {
  pageTitle("フォーム")
  load(s)
  // 回答が届いたら数を更新する
  watch(Store.changes, false)((_, _) => load(s))
}

def create(): Unit = Dialog.prompt("新しいフォームの題名", "無題のフォーム", "作る") { t =>
  Store.report(Api.createForm(t))(f => Router.go(Paths.form(f.id)))
}

def open(f: FormSummaryDto): Unit = Router.go(if (f.responseCount > 0) Paths.responses(f.id) else Paths.form(f.id))

def empty(s: State) = computed { s.forms.v.isEmpty && !s.loading.v }
def mine(f: FormSummaryDto): Boolean = Store.myId.contains(f.owner.id)
</script>

<template>
  <div class="page">
    <div class="row head">
      <h1 class="grow">フォーム</h1>
      <button class="primary" @click="create()">＋ 新しいフォーム</button>
    </div>
    <form class="row filter" @submit.prevent="load(s)">
      <input type="search" v-model="s.q" placeholder="題名で絞り込み" class="grow">
      <button type="submit">検索</button>
      <label v-if="Store.isAdmin" class="check small"><input type="checkbox" v-model="s.all" @change="load(s)"> すべての人のフォーム（管理者）</label>
    </form>
    <div v-if="empty(s)" class="empty card">
      <img src="/logo.svg" alt="" width="96" height="96">
      <p>フォームはまだありません。「新しいフォーム」から、アンケートや申し込みのフォームを作れます。</p>
    </div>
    <div class="cards">
      <a v-for="f in s.forms" :key="f.id" class="card form" :href="Paths.form(f.id)" @click.prevent="open(f)">
        <div class="row">
          <b class="grow name">{{ f.title }}</b>
          <span :class='"badge st-" + f.status'>{{ FormStatus.label(f.status) }}</span>
        </div>
        <div class="count"><span class="num">{{ f.responseCount }}</span> 件の回答</div>
        <div class="muted small">{{ f.lastResponseAt.map(t => "最後の回答 " + Fmt.ago(t)).getOrElse("まだ回答はありません") }}・質問 {{ f.questionCount }} 個</div>
        <div class="row muted small foot">
          <Avatar :user="Some(f.owner)" :size="18" /><span class="grow">{{ if (mine(f)) "自分のフォーム" else f.owner.name }}</span>
          <span>{{ Fmt.ago(f.updatedAt) }}に更新</span>
        </div>
      </a>
    </div>
  </div>
</template>

<style scoped>
.head { margin-bottom: .4rem; }
.filter { margin-bottom: 1rem; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(270px, 1fr)); gap: .9rem; }
.form { display: flex; flex-direction: column; gap: .35rem; color: var(--fg); border-top: 4px solid var(--accent); transition: box-shadow .15s, transform .15s; }
.form:hover { text-decoration: none; box-shadow: var(--shadow); transform: translateY(-1px); }
.name { overflow-wrap: anywhere; font-size: 1.02rem; }
.count { margin-top: .2rem; }
.num { font-size: 1.6rem; font-weight: 700; color: var(--accent); }
.foot { margin-top: .3rem; }
.empty img { opacity: .9; }
</style>
