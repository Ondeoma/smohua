<script lang="scala">
import smohua.web.*

// 色の入力（色を選ぶ欄と、#rrggbb を打つ欄）. 空は「既定の色」.
case class Props(label: String, value: RV[String])

/** 色を選ぶ欄は空を持てないので、既定のときは見本の色を出しておく. */
def picker(props: Props) = props.value.mapC(v => if (v.matches("^#[0-9a-fA-F]{6}$")) v else "#888888")
def custom(props: Props) = props.value.mapC(_.trim.nonEmpty)
</script>

<template>
  <label class="cf">
    <span class="cf-label">{{ props.label }}</span>
    <input type="color" :value="picker(props)" @input="props.value := Dom.value(event)">
    <input type="text" v-model="props.value" placeholder="既定" maxlength="7" class="cf-text">
    <button v-if="custom(props)" type="button" class="small ghost" @click='props.value := ""'>既定に戻す</button>
  </label>
</template>

<style scoped>
.cf { display: flex; align-items: center; gap: .5rem; padding: .2rem 0; }
.cf-label { min-width: 7rem; }
.cf input[type=color] { width: 2.4rem; height: 2rem; padding: 0; border: 1px solid var(--line); border-radius: 6px; background: none; }
.cf-text { width: 6.5rem; font-family: monospace; }
</style>
