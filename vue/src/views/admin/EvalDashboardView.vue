<template>
  <section class="eval-dashboard">
    <!-- 指标卡片 -->
    <div class="metric-grid">
      <div class="metric-card" v-for="card in metricCards" :key="card.label">
        <div class="metric-header">
          <component :is="card.icon" class="metric-icon" :class="`metric-icon-${card.color}`" />
          <span class="metric-label">{{ card.label }}</span>
        </div>
        <div class="metric-value" :class="`metric-value-${card.color}`">
          {{ card.value }}
        </div>
        <div class="metric-change" :class="card.changeDir">
          {{ card.change }}
        </div>
      </div>
    </div>

    <!-- 操作按钮 -->
    <div class="action-bar">
      <button class="btn btn-primary" :disabled="running" @click="startNewRun">
        <span v-if="running" class="spinner"></span>
        {{ running ? '评估运行中...' : '▶ 运行评估' }}
      </button>
      <button v-if="running" class="btn btn-danger" @click="stopRun">■ 停止</button>
      <button class="btn btn-outline" @click="refreshAll">🔄 刷新</button>
      <span class="run-status" v-if="running">
        <span class="status-dot status-dot-running"></span> 评估正在运行，请耐心等待
      </span>
    </div>

    <!-- 最新运行详情 -->
    <article class="panel-card" v-if="latestRun">
      <div class="panel-title"><h3>最新运行</h3></div>
      <div class="run-info-grid">
        <div class="info-item">
          <span class="info-label">运行名称</span>
          <span class="info-value">{{ latestRun.runName }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">测试集规模</span>
          <span class="info-value">{{ latestRun.datasetSize }} 题</span>
        </div>
        <div class="info-item">
          <span class="info-label">平均检索耗时</span>
          <span class="info-value">{{ latestRun.avgLatencyMs }} ms</span>
        </div>
        <div class="info-item">
          <span class="info-label">完成时间</span>
          <span class="info-value">{{ formatTime(latestRun.completedAt) }}</span>
        </div>
      </div>
    </article>

    <!-- 历史运行列表 -->
    <article class="panel-card">
      <div class="panel-title">
        <h3>运行历史</h3>
        <RouterLink to="/admin/eval/runs" class="link-more">查看全部 →</RouterLink>
      </div>
      <table class="data-table" v-if="runs.length">
        <thead>
          <tr>
            <th>运行名称</th>
            <th>Precision</th>
            <th>Recall</th>
            <th>测试集</th>
            <th>耗时</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in runs.slice(0, 5)" :key="run.id">
            <td class="cell-name">{{ run.runName }}</td>
            <td class="cell-number" :class="scoreColor(run.avgContextPrecision)">
              {{ formatScore(run.avgContextPrecision) }}
            </td>
            <td class="cell-number" :class="scoreColor(run.avgContextRecall)">
              {{ formatScore(run.avgContextRecall) }}
            </td>
            <td>{{ run.datasetSize }}</td>
            <td>{{ run.avgLatencyMs }}ms</td>
            <td><StatusBadge :status="runStatus(run)" /></td>
            <td>
              <button class="btn-link" @click="viewRunDetail(run)">详情</button>
              <button class="btn-link" @click="viewRunResults(run)">结果</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">
        <p>暂无评估运行记录</p>
        <p class="empty-hint">点击「运行评估」开始第一次评估</p>
      </div>
    </article>

    <!-- 指标趋势（基于运行记录） -->
    <article class="panel-card" v-if="chartRuns.length >= 2">
      <div class="panel-title"><h3>指标趋势（最近 {{ chartRuns.length }} 次运行）</h3></div>
      <div class="trend-chart-wrap">
        <svg :viewBox="`0 0 ${svgW} ${svgH}`" class="trend-svg" preserveAspectRatio="xMidYMid meet">
          <!-- 网格线 -->
          <line v-for="g in 4" :key="'g'+g" :x1="40" :y1="10 + g * 33" :x2="svgW - 10" :y2="10 + g * 33" stroke="#eee" stroke-width="1" />
          <!-- Y 轴标签 -->
          <text v-for="(l, i) in ['1.0','0.75','0.5','0.25','0']" :key="'y'+i" x="36" :y="14 + i * 33" text-anchor="end" fill="#999" font-size="10">{{ l }}</text>
          <!-- Precision 线 -->
          <polyline v-if="precisionLine" :points="precisionLine" fill="none" stroke="var(--color-primary)" stroke-width="2" />
          <!-- Recall 线 -->
          <polyline v-if="recallLine" :points="recallLine" fill="none" stroke="var(--color-success)" stroke-width="2" />
          <!-- X 轴日期 -->
          <text v-for="(r, i) in chartRuns" :key="'x'+i"
            :x="40 + (i / Math.max(chartRuns.length - 1, 1)) * (svgW - 50)"
            y="152" text-anchor="middle" fill="#999" font-size="9">
            {{ formatShortDate(r.completedAt) }}
          </text>
          <!-- 图例 -->
          <rect x="8" y="2" width="10" height="10" fill="var(--color-primary)" rx="2" />
          <text x="22" y="11" fill="var(--color-primary)" font-size="11">Precision</text>
          <rect x="98" y="2" width="10" height="10" fill="var(--color-success)" rx="2" />
          <text x="112" y="11" fill="var(--color-success)" font-size="11">Recall</text>
        </svg>
      </div>
    </article>
  </section>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  ChartBarSquareIcon,
  MagnifyingGlassCircleIcon,
  CheckBadgeIcon,
  DocumentTextIcon
} from '@heroicons/vue/24/outline'
import { evalApi } from '../../api/api'

const router = useRouter()
const running = ref(false)
const runs = ref([])
const latestRun = ref(null)
const pollTimer = ref(null)

const svgW = 600
const svgH = 160

// 取最近完成的运行，翻转使按时间正序
const chartRuns = computed(() =>
  runs.value.filter(r => r.runStatus === 3).slice(0, 10).reverse()
)

const precisionLine = computed(() => calcLine(chartRuns.value, 'avgContextPrecision'))
const recallLine = computed(() => calcLine(chartRuns.value, 'avgContextRecall'))

function calcLine(data, field) {
  const w = svgW - 50
  const h = svgH - 30
  return data.map((r, i) => {
    const x = 40 + (i / Math.max(data.length - 1, 1)) * w
    const val = r[field]
    const y = 10 + (1 - (val != null ? Number(val) : 0)) * h
    return `${x},${y}`
  }).join(' ')
}

function formatShortDate(t) {
  if (!t) return ''
  const d = new Date(t)
  return (d.getMonth() + 1) + '/' + d.getDate()
}

// 指标卡片
const metricCards = computed(() => {
  const r = latestRun.value
  return [
    { label: 'Context Precision', value: formatScore(r?.avgContextPrecision), change: r?.precisionChange || '—', changeDir: '', icon: MagnifyingGlassCircleIcon, color: 'blue' },
    { label: 'Context Recall', value: formatScore(r?.avgContextRecall), change: r?.recallChange || '—', changeDir: '', icon: CheckBadgeIcon, color: 'green' },
    { label: 'Faithfulness', value: formatScore(r?.avgFaithfulness), change: r?.faithfulnessChange || '—', changeDir: '', icon: DocumentTextIcon, color: 'amber' },
    { label: 'Answer Relevancy', value: formatScore(r?.avgAnswerRelevancy), change: r?.relevancyChange || '—', changeDir: '', icon: ChartBarSquareIcon, color: 'purple' }
  ]
})

// ── 方法 ──
async function refreshAll() {
  try {
    const [runList, summary] = await Promise.all([
      evalApi.listRuns(),
      evalApi.getDashboardSummary()
    ])
    runs.value = Array.isArray(runList) ? runList : []
    if (runs.value.length) latestRun.value = runs.value[0]
    if (summary?.latestRun) {
      latestRun.value = { ...latestRun.value, ...summary.latestRun,
        precisionChange: summary.precisionChange,
        recallChange: summary.recallChange,
        faithfulnessChange: summary.faithfulnessChange,
        relevancyChange: summary.relevancyChange
      }
    }
  } catch (e) {
    console.error('加载评估数据失败', e)
  }
}

async function startNewRun() {
  running.value = true
  try {
    await evalApi.startRun(
      new Date().toISOString().slice(0, 16).replace('T', '-'),
      'manual'
    )
    // 轮询等待完成
    pollTimer.value = setInterval(async () => {
      try {
        const st = await evalApi.getRunStatus()
        if (!st?.running) {
          clearInterval(pollTimer.value)
          running.value = false
          await refreshAll()
        }
      } catch { /* ignore */ }
    }, 3000)
  } catch (e) {
    console.error('启动评估失败', e)
    running.value = false
  }
}

async function stopRun() {
  try {
    await evalApi.stopRun()
    running.value = false
    clearInterval(pollTimer.value)
  } catch (e) {
    console.error('停止评估失败', e)
  }
}

function viewRunDetail(run) {
  router.push(`/admin/eval/runs/${run.id}`)
}

function viewRunResults(run) {
  router.push(`/admin/eval/runs/${run.id}/results`)
}

function formatScore(val) {
  if (val == null) return '—'
  return Number(val).toFixed(4)
}

function formatTime(t) {
  if (!t) return '—'
  return new Date(t).toLocaleString()
}

function scoreColor(val) {
  if (val == null) return ''
  const n = Number(val)
  if (n >= 0.9) return 'score-excellent'
  if (n >= 0.7) return 'score-good'
  return 'score-poor'
}

function runStatus(run) {
  return run.runStatus === 3 ? { label: '已完成', code: 'success' }
    : run.runStatus === 2 ? { label: '运行中', code: 'running' }
    : run.runStatus === 4 ? { label: '失败', code: 'failed' }
    : { label: '待运行', code: 'pending' }
}

onMounted(refreshAll)
onUnmounted(() => clearInterval(pollTimer.value))
</script>

<style scoped>
.eval-dashboard { display: flex; flex-direction: column; gap: 20px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
.metric-card { background: #fff; border-radius: var(--radius-md); padding: 20px; border: 1px solid var(--color-border); }
.metric-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.metric-icon { width: 20px; height: 20px; }
.metric-icon-blue { color: var(--color-primary); }
.metric-icon-green { color: var(--color-success); }
.metric-icon-amber { color: #d97706; }
.metric-icon-purple { color: #7c3aed; }
.metric-label { font-size: 12px; color: var(--color-muted); font-weight: 500; }
.metric-value { font-size: 28px; font-weight: 700; line-height: 1.2; }
.metric-value-blue { color: var(--color-primary); }
.metric-value-green { color: var(--color-success); }
.metric-value-amber { color: #d97706; }
.metric-value-purple { color: #7c3aed; }
.metric-change { font-size: 12px; margin-top: 4px; }
.metric-change.up { color: var(--color-success); }
.metric-change.down { color: var(--color-danger); }
.action-bar { display: flex; align-items: center; gap: 12px; }
.run-status { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--color-muted); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; }
.status-dot-running { background: var(--color-primary); animation: pulse 1.5s infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
.run-info-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
.info-item { display: flex; flex-direction: column; gap: 4px; }
.info-label { font-size: 12px; color: var(--color-muted); }
.info-value { font-size: 14px; font-weight: 600; color: var(--color-text); }
.data-table { width: 100%; border-collapse: collapse; }
.data-table th, .data-table td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--color-border); font-size: 13px; }
.data-table th { font-weight: 600; color: var(--color-muted); }
.cell-name { font-weight: 500; }
.cell-number { font-variant-numeric: tabular-nums; font-weight: 600; }
.score-excellent { color: var(--color-success); }
.score-good { color: var(--color-primary); }
.score-poor { color: var(--color-danger); }
.btn-link { background: none; border: none; color: var(--color-primary); cursor: pointer; font-size: 13px; padding: 2px 6px; }
.btn-link:hover { text-decoration: underline; }
.link-more { font-size: 13px; color: var(--color-primary); text-decoration: none; }
.empty-state { text-align: center; padding: 40px 20px; color: var(--color-muted); }
.empty-hint { font-size: 13px; margin-top: 4px; }
.trend-chart-wrap { width: 100%; height: 180px; }
.trend-svg { width: 100%; height: 100%; }
</style>
