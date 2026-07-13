<template>
  <section class="eval-runs">
    <div class="top-bar">
      <h3>评估运行历史</h3>
      <div class="top-actions">
        <RouterLink to="/admin/eval/runs" class="btn btn-outline btn-sm">← 返回</RouterLink>
        <button class="btn btn-primary btn-sm" @click="refresh">🔄 刷新</button>
      </div>
    </div>

    <!-- 运行列表 -->
    <table class="data-table">
      <thead>
        <tr>
          <th>ID</th>
          <th>运行名称</th>
          <th>类型</th>
          <th>Precision</th>
          <th>Recall</th>
          <th>Faithfulness</th>
          <th>Relevancy</th>
          <th>测试集</th>
          <th>耗时(ms)</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="run in runs" :key="run.id">
          <td>{{ run.id }}</td>
          <td class="cell-name">{{ run.runName }}</td>
          <td><code class="tag">{{ run.runType }}</code></td>
          <td class="cell-number" :class="scoreColor(run.avgContextPrecision)">{{ fmt(run.avgContextPrecision) }}</td>
          <td class="cell-number" :class="scoreColor(run.avgContextRecall)">{{ fmt(run.avgContextRecall) }}</td>
          <td class="cell-number">{{ fmt(run.avgFaithfulness) }}</td>
          <td class="cell-number">{{ fmt(run.avgAnswerRelevancy) }}</td>
          <td>{{ run.datasetSize }}</td>
          <td>{{ run.avgLatencyMs }}</td>
          <td><StatusBadge :label="statusLabel(run)" :code="statusCode(run)" /></td>
          <td class="cell-actions">
            <button class="btn-link" @click="showDetail(run)">详情</button>
            <button class="btn-link" @click="showResults(run)">结果</button>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 详情抽屉 -->
    <transition name="drawer-slide">
      <aside v-if="detailRun" class="detail-drawer">
        <div class="drawer-header">
          <h4>运行详情 · {{ detailRun.runName }}</h4>
          <button class="icon-button" @click="detailRun = null"><XMarkIcon class="icon-sm" /></button>
        </div>
        <div class="drawer-body">
          <div class="detail-grid">
            <div class="detail-field" v-for="(val, key) in detailFields" :key="key">
              <span class="detail-label">{{ key }}</span>
              <span class="detail-value">{{ val }}</span>
            </div>
          </div>
          <h5 style="margin-top:16px;">配置快照</h5>
          <pre class="config-snapshot">{{ formatConfig(detailRun.configSnapshot) }}</pre>
          <h5 style="margin-top:16px;">优化建议</h5>
          <div v-if="advice.length">
            <div v-for="a in advice" :key="a.title" class="advice-card" :class="`advice-${a.priority}`">
              <strong>{{ a.title }}</strong>
              <pre class="advice-text">{{ a.suggestion }}</pre>
            </div>
          </div>
          <div v-else class="empty-hint">加载中...</div>
        </div>
      </aside>
    </transition>

    <!-- 结果明细弹窗 -->
    <transition name="modal-fade">
      <div v-if="resultsRun" class="modal-overlay" @click.self="resultsRun = null">
        <div class="modal-content">
          <div class="modal-header">
            <h4>问题明细 · {{ resultsRun.runName }}</h4>
            <button class="icon-button" @click="resultsRun = null"><XMarkIcon class="icon-sm" /></button>
          </div>
          <div class="modal-body">
            <table class="data-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>问题</th>
                  <th>Precision</th>
                  <th>Recall</th>
                  <th>检索耗时</th>
                  <th>TopK</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(q, i) in questionResults" :key="q.id">
                  <td>{{ i + 1 }}</td>
                  <td class="cell-question">{{ q.question }}</td>
                  <td class="cell-number" :class="scoreColor(q.contextPrecision)">{{ fmt(q.contextPrecision) }}</td>
                  <td class="cell-number" :class="scoreColor(q.contextRecall)">{{ fmt(q.contextRecall) }}</td>
                  <td>{{ q.retrievalLatencyMs }}ms</td>
                  <td>{{ q.finalTopK }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </transition>
  </section>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { XMarkIcon } from '@heroicons/vue/24/outline'
import { evalApi } from '../../api/api'
import StatusBadge from '../../components/admin/AdminStatusBadge.vue'

const route = useRoute()
const runs = ref([])
const detailRun = ref(null)
const advice = ref([])
const resultsRun = ref(null)
const questionResults = ref([])

const detailFields = computed(() => {
  const r = detailRun.value
  if (!r) return {}
  return {
    '运行名称': r.runName,
    '类型': r.runType,
    '测试集': r.datasetSize,
    'Precision': fmt(r.avgContextPrecision),
    'Recall': fmt(r.avgContextRecall),
    'Faithfulness': fmt(r.avgFaithfulness),
    'Relevancy': fmt(r.avgAnswerRelevancy),
    '平均耗时': r.avgLatencyMs + ' ms',
    '开始时间': formatTime(r.startedAt),
    '完成时间': formatTime(r.completedAt),
    '错误信息': r.errorMessage || '无'
  }
})

async function refresh() {
  try {
    runs.value = await evalApi.listRuns() || []
  } catch (e) {
    console.error('加载运行历史失败', e)
  }
}

async function showDetail(run) {
  detailRun.value = run
  try {
    const result = await evalApi.analyzeRun(run.id)
    advice.value = result?.advice || []
  } catch { advice.value = [] }
}

async function showResults(run) {
  resultsRun.value = run
  try {
    questionResults.value = await evalApi.listResults(run.id) || []
  } catch { questionResults.value = [] }
}

function fmt(val) {
  if (val == null) return '—'
  return Number(val).toFixed(4)
}
function formatTime(t) {
  if (!t) return '—'
  return new Date(t).toLocaleString()
}
function formatConfig(json) {
  try { return JSON.stringify(JSON.parse(json), null, 2) } catch { return json }
}
function scoreColor(val) {
  if (val == null) return ''
  const n = Number(val)
  if (n >= 0.9) return 'score-excellent'
  if (n >= 0.7) return 'score-good'
  return 'score-poor'
}
function statusLabel(run) {
  return run.runStatus === 3 ? '已完成' : run.runStatus === 2 ? '运行中' : run.runStatus === 4 ? '失败' : '待运行'
}
function statusCode(run) {
  return run.runStatus === 3 ? 'index_success' : run.runStatus === 2 ? 'parsing' : 'parse_failed'
}

onMounted(refresh)
</script>

<style scoped>
.eval-runs { display: flex; flex-direction: column; gap: 16px; }
.top-bar { display: flex; justify-content: space-between; align-items: center; }
.top-bar h3 { margin: 0; }
.top-actions { display: flex; gap: 8px; align-items: center; }
.data-table { width: 100%; border-collapse: collapse; background: #fff; border-radius: var(--radius-md); overflow: hidden; }
.data-table th, .data-table td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--color-border); font-size: 13px; }
.data-table th { font-weight: 600; color: var(--color-muted); background: var(--color-surface-soft); }
.cell-name { font-weight: 500; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cell-number { font-variant-numeric: tabular-nums; font-weight: 600; }
.cell-question { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.score-excellent { color: var(--color-success); }
.score-good { color: var(--color-primary); }
.score-poor { color: var(--color-danger); }
.tag { background: var(--color-surface-soft); padding: 2px 6px; border-radius: 4px; font-size: 11px; }
.btn-link { background: none; border: none; color: var(--color-primary); cursor: pointer; font-size: 13px; padding: 2px 6px; }
.btn-link:hover { text-decoration: underline; }
.cell-actions { white-space: nowrap; }
.detail-drawer { position: fixed; right: 0; top: 0; bottom: 0; width: 480px; background: #fff; z-index: 20; box-shadow: var(--shadow-lg); display: flex; flex-direction: column; }
.drawer-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid var(--color-border); }
.drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.detail-field { display: flex; flex-direction: column; gap: 2px; }
.detail-label { font-size: 11px; color: var(--color-muted); }
.detail-value { font-size: 14px; font-weight: 500; }
.config-snapshot { background: var(--color-surface-soft); padding: 12px; border-radius: var(--radius-sm); font-size: 12px; overflow-x: auto; }
.icon-sm { width: 18px; height: 18px; }
.icon-button { border: none; background: none; cursor: pointer; color: var(--color-muted); }
.advice-card { border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 12px; margin-top: 8px; }
.advice-high { border-left: 3px solid var(--color-danger); }
.advice-medium { border-left: 3px solid #d97706; }
.advice-low { border-left: 3px solid var(--color-primary); }
.advice-text { font-size: 12px; color: var(--color-muted); margin-top: 6px; white-space: pre-wrap; }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 30; display: flex; align-items: center; justify-content: center; }
.modal-content { background: #fff; border-radius: var(--radius-md); max-width: 900px; width: 90%; max-height: 80vh; display: flex; flex-direction: column; }
.modal-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid var(--color-border); }
.modal-body { flex: 1; overflow-y: auto; padding: 16px 20px; }
.empty-hint { font-size: 13px; color: var(--color-muted); padding: 8px 0; }
.drawer-slide-enter-active, .drawer-slide-leave-active { transition: transform 0.2s ease; }
.drawer-slide-enter-from, .drawer-slide-leave-to { transform: translateX(100%); }
.modal-fade-enter-active, .modal-fade-leave-active { transition: opacity 0.15s ease; }
.modal-fade-enter-from, .modal-fade-leave-to { opacity: 0; }
</style>
