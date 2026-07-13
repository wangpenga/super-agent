<template>
  <section class="eval-review">
    <!-- 筛选栏 + 从对话导入 -->
    <div class="filter-bar">
      <div class="filter-group">
        <label>抽检状态</label>
        <select v-model="filterStatus" @change="refresh">
          <option :value="null">全部</option>
          <option :value="0">待处理</option>
          <option :value="1">已生成答案</option>
          <option :value="2">已评分</option>
        </select>
      </div>
      <div class="filter-group">
        <label>文档 ID</label>
        <input v-model="filterDocId" type="text" placeholder="按文档 ID 筛选" @change="refresh" />
      </div>
      <div class="filter-actions">
        <button class="btn btn-primary btn-sm" @click="refresh">🔄 刷新</button>
      </div>
      <div class="filter-sep"></div>
      <div class="filter-group">
        <label>从对话导入</label>
        <input v-model="importDocId" type="text" placeholder="文档 ID" class="input-xs" style="width:100px;" />
        <button class="btn btn-outline btn-sm" :disabled="!importDocId || importing" @click="importConversations">
          {{ importing ? '导入中...' : '导入' }}
        </button>
      </div>
      <div class="filter-summary" v-if="items.length">
        共 <strong>{{ items.length }}</strong> 条
        <span class="summary-dot" v-if="pendingCount > 0">
          ，<span class="text-warning">{{ pendingCount }} 条待处理</span>
        </span>
      </div>
    </div>

    <!-- 列表 -->
    <div class="review-list" v-if="items.length">
      <div
        v-for="item in items"
        :key="item.datasetId"
        class="review-card"
        :class="`review-status-${item.reviewStatus}`"
      >
        <div class="review-header" @click="toggleExpand(item.datasetId)">
          <div class="review-meta">
            <span class="status-badge-sm" :class="`status-${item.reviewStatus}`">
              {{ statusText(item.reviewStatus) }}
            </span>
            <span class="doc-id">文档 {{ shortId(item.documentId) }}</span>
            <span class="source-tag">{{ item.source }}</span>
          </div>
          <div class="review-question">{{ item.question }}</div>
          <div class="review-score-preview" v-if="item.humanScore">
            {{ '⭐'.repeat(item.humanScore) }}
          </div>
          <button class="expand-btn" @click.stop="toggleExpand(item.datasetId)">
            {{ expandedId === item.datasetId ? '收起' : '展开' }}
          </button>
        </div>

        <!-- 展开详情 -->
        <div v-if="expandedId === item.datasetId" class="review-detail">
          <!-- 检索到的 chunks -->
          <div class="detail-section">
            <h4>📄 检索到的 chunks</h4>
            <div class="chunk-list" v-if="chunks[item.datasetId]">
              <div v-for="(chunk, i) in chunks[item.datasetId]" :key="i" class="chunk-item">
                <div class="chunk-head">
                  <span>#{{ i + 1 }}</span>
                  <span class="chunk-score" v-if="chunk.rerankScore != null">
                    rerank: {{ Number(chunk.rerankScore).toFixed(4) }}
                  </span>
                  <span class="chunk-score" v-else-if="chunk.similarityScore != null">
                    相似度: {{ Number(chunk.similarityScore).toFixed(4) }}
                  </span>
                </div>
                <div class="chunk-path">{{ chunk.sectionPath || chunk.documentName || '' }}</div>
                <pre class="chunk-text">{{ chunk.text }}</pre>
              </div>
            </div>
            <button v-if="!chunks[item.datasetId]" class="btn-link" @click="fetchChunks(item)">
              🔍 加载 chunks
            </button>
          </div>

          <!-- ════════════ 答案三栏对比 ════════════ -->
          <div class="answer-comparison">
            <!-- 栏1：实际大模型回答（来源对话日志） -->
            <div class="answer-col" :class="{ 'col-highlight': item.actualAnswer }">
              <div class="answer-col-header">
                <h4>💬 实际大模型回答</h4>
                <span v-if="item.actualLatencyMs" class="col-meta">{{ item.actualLatencyMs }}ms</span>
              </div>
              <div v-if="item.actualAnswer" class="answer-content">{{ item.actualAnswer }}</div>
              <div v-else-if="item.source === 'conversation_log'" class="answer-placeholder">
                加载中...
              </div>
              <div v-else class="answer-placeholder">
                此条数据非来自真实对话<br /><span class="hint">没有当时的回答记录</span>
              </div>
            </div>

            <!-- 栏2：LLM 基于 chunks 生成的答案 -->
            <div class="answer-col">
              <div class="answer-col-header">
                <h4>🤖 离线生成答案</h4>
                <button
                  v-if="!item.generatedAnswer || item.generatedAnswer.startsWith('⚠️') || item.generatedAnswer.startsWith('❌')"
                  class="btn btn-primary btn-xs"
                  :disabled="generatingId === item.datasetId"
                  @click="generateAnswer(item)"
                >
                  <span v-if="generatingId === item.datasetId" class="spinner"></span>
                  {{ generatingId === item.datasetId ? '生成中...' : '生成' }}
                </button>
                <button v-else class="btn btn-outline btn-xs" @click="generateAnswer(item)">
                  重生成
                </button>
              </div>
              <div v-if="item.generatedAnswer && !item.generatedAnswer.startsWith('⚠️') && !item.generatedAnswer.startsWith('❌')" class="answer-content">
                {{ item.generatedAnswer }}
              </div>
              <div v-else-if="item.generatedAnswer" class="answer-content answer-error">
                {{ item.generatedAnswer }}
              </div>
              <div v-else class="answer-placeholder">
                点击「生成」按钮基于检索 chunks 生成<br /><span class="hint">可用于和实际回答对比</span>
              </div>
            </div>

            <!-- 栏3：参考答案 -->
            <div class="answer-col">
              <div class="answer-col-header">
                <h4>📝 参考答案</h4>
                <button class="btn btn-outline btn-xs" @click="startEditReference(item)">编辑</button>
              </div>
              <div v-if="item.referenceAnswer" class="answer-content">{{ item.referenceAnswer }}</div>
              <div v-else class="answer-placeholder">
                暂无参考答案<br /><span class="hint">点击「编辑」录入标准答案</span>
              </div>
            </div>
          </div>

          <!-- 编辑参考答案弹窗 -->
          <div v-if="editRefId === item.datasetId" class="ref-edit-overlay">
            <div class="ref-edit-box">
              <h4>编辑参考答案</h4>
              <textarea v-model="editRefText" rows="6" placeholder="输入标准答案..."></textarea>
              <div class="ref-edit-actions">
                <button class="btn btn-primary btn-sm" @click="saveReference(item)">保存</button>
                <button class="btn btn-outline btn-sm" @click="editRefId = null">取消</button>
              </div>
            </div>
          </div>

          <!-- 评分 -->
          <div class="detail-section">
            <div class="section-header">
              <h4>⭐ 人工评分</h4>
            </div>
            <div class="rating-area">
              <div class="star-group">
                <button
                  v-for="s in 5"
                  :key="s"
                  class="star-btn"
                  :class="{ active: (ratingScore === s) }"
                  @click="ratingScore = s"
                >{{ ratingScore >= s ? '⭐' : '☆' }}</button>
                <span class="rating-label" v-if="ratingScore">
                  {{ ratingScore }} 分/5 分 —
                  {{ ratingLabel(ratingScore) }}
                </span>
                <span class="rating-label" v-else>点击星星评分</span>
              </div>
              <textarea
                v-model="ratingComment"
                placeholder="评语（可选）"
                rows="2"
                class="rating-comment"
              ></textarea>
              <button
                class="btn btn-primary btn-sm"
                :disabled="!ratingScore || ratingSaving"
                @click="submitRating(item)"
              >
                {{ ratingSaving ? '保存中...' : '提交评分' }}
              </button>
              <span v-if="item.humanScore" class="rating-existing">
                已评 {{ item.humanScore }} 分
                <span v-if="item.humanComment">· {{ item.humanComment }}</span>
              </span>
            </div>
          </div>

          <!-- 文档归属信息 -->
          <div class="detail-section section-meta">
            <div class="meta-grid">
              <div><span class="meta-label">数据集 ID</span><span class="meta-val">{{ item.datasetId }}</span></div>
              <div><span class="meta-label">文档 ID</span><span class="meta-val">{{ item.documentId }}</span></div>
              <div><span class="meta-label">来源</span><span class="meta-val">{{ item.source }}</span></div>
              <div><span class="meta-label">ground truth chunks</span><span class="meta-val">{{ item.groundTruthChunkIds }}</span></div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="empty-state">
      <p>暂无抽检数据</p>
      <p class="empty-hint">请先生成测试集（文档详情页 → 生成测试集），然后在此页面进行人工抽检</p>
    </div>
  </section>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { evalApi } from '../../api/api'

const items = ref([])
const expandedId = ref(null)
// 展开详情缓存（存放 getReviewDetail 返回的字段如 actualAnswer）
const detailCache = ref({})
const filterStatus = ref(null)
const filterDocId = ref('')
const chunks = ref({})
const generatingId = ref(null)
const editRefId = ref(null)
const editRefText = ref('')
const ratingScore = ref(null)
const ratingComment = ref('')
const ratingSaving = ref(false)

const pendingCount = ref(0)

async function refresh() {
  try {
    const params = {}
    if (filterStatus.value != null) params.reviewStatus = filterStatus.value
    if (filterDocId.value) params.documentId = filterDocId.value
    items.value = await evalApi.listReview(params) || []
    pendingCount.value = items.value.filter(i => i.reviewStatus === 0).length
  } catch (e) {
    console.error('加载抽检列表失败', e)
  }
}

function toggleExpand(id) {
  if (expandedId.value === id) {
    expandedId.value = null
    return
  }
  expandedId.value = id
  ratingScore.value = null
  ratingComment.value = ''

  // 展开时异步加载实际答案
  loadActualAnswer(id)
}

async function loadActualAnswer(id) {
  if (detailCache.value[id]) {
    // 已缓存，直接更新到 items
    const item = items.value.find(i => i.datasetId === id)
    if (item) Object.assign(item, detailCache.value[id])
    return
  }
  try {
    const detail = await evalApi.getReviewDetail(id)
    if (detail) {
      detailCache.value[id] = {
        actualAnswer: detail.actualAnswer,
        actualLatencyMs: detail.actualLatencyMs,
        actualSources: detail.actualSources
      }
      const item = items.value.find(i => i.datasetId === id)
      if (item) Object.assign(item, detailCache.value[id])
    }
  } catch (e) {
    console.error('加载实际答案失败', e)
  }
}

async function importConversations() {
  if (!importDocId.value) return
  importing.value = true
  try {
    const result = await evalApi.importConversations(Number(importDocId.value), 20)
    alert(result?.message || '导入完成')
    await refresh()
  } catch (e) {
    alert('导入失败: ' + e.message)
  }
  importing.value = false
}

async function fetchChunks(item) {
  try {
    const result = await evalApi.getReviewDetail(item.datasetId)
    // chunks 信息需要单独从主服务或 eval 服务获取
    // 简化处理：使用已有的 groundTruthChunkIds 显示
    chunks.value[item.datasetId] = [{ text: 'Chunk ID: ' + item.groundTruthChunkIds }]
  } catch (e) {
    console.error('加载 chunks 失败', e)
  }
}

async function generateAnswer(item) {
  generatingId.value = item.datasetId
  try {
    const result = await evalApi.generateAnswer(item.datasetId)
    item.generatedAnswer = result.answer
    item.reviewStatus = 1
  } catch (e) {
    item.generatedAnswer = '❌ ' + e.message
  }
  generatingId.value = null
}

function startEditReference(item) {
  editRefId.value = item.datasetId
  editRefText.value = item.referenceAnswer || ''
}

async function saveReference(item) {
  try {
    await evalApi.saveReferenceAnswer(item.datasetId, editRefText.value)
    item.referenceAnswer = editRefText.value
    editRefId.value = null
  } catch (e) {
    alert('保存失败: ' + e.message)
  }
}

async function submitRating(item) {
  if (!ratingScore.value) return
  ratingSaving.value = true
  try {
    await evalApi.rateReview(item.datasetId, ratingScore.value, ratingComment.value)
    item.humanScore = ratingScore.value
    item.humanComment = ratingComment.value
    item.reviewStatus = 2
    alert('评分已保存')
  } catch (e) {
    alert('保存失败: ' + e.message)
  }
  ratingSaving.value = false
}

function statusText(s) {
  return s === 2 ? '已评分' : s === 1 ? '已生成答案' : '待处理'
}

function shortId(id) {
  if (!id) return '-'
  const s = String(id)
  return s.length > 8 ? '...' + s.slice(-8) : s
}

function ratingLabel(s) {
  return ['', '很差', '较差', '一般', '良好', '优秀'][s] || ''
}

watch(filterStatus, () => refresh())
onMounted(refresh)
</script>

<style scoped>
.eval-review { display: flex; flex-direction: column; gap: 12px; }

/* 筛选栏 */
.filter-bar { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; background: #fff; padding: 12px 16px; border-radius: var(--radius-md); border: 1px solid var(--color-border); }
.filter-group { display: flex; align-items: center; gap: 6px; }
.filter-group label { font-size: 12px; color: var(--color-muted); white-space: nowrap; }
.filter-group select, .filter-group input { padding: 4px 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: 13px; }
.filter-actions { margin-left: auto; }
.filter-summary { font-size: 13px; color: var(--color-muted); }
.text-warning { color: #d97706; }
.summary-dot { font-size: 13px; }

/* 卡片列表 */
.review-list { display: flex; flex-direction: column; gap: 8px; }
.review-card { background: #fff; border: 1px solid var(--color-border); border-radius: var(--radius-md); overflow: hidden; }
.review-card.review-status-0 { border-left: 3px solid var(--color-muted); }
.review-card.review-status-1 { border-left: 3px solid var(--color-primary); }
.review-card.review-status-2 { border-left: 3px solid var(--color-success); }
.review-header { display: flex; align-items: center; gap: 12px; padding: 12px 16px; cursor: pointer; }
.review-header:hover { background: var(--color-surface-soft); }
.review-meta { display: flex; align-items: center; gap: 6px; flex: none; }
.status-badge-sm { font-size: 11px; padding: 2px 8px; border-radius: 10px; background: var(--color-surface-soft); }
.status-0 { background: #f3f4f6; color: #6b7280; }
.status-1 { background: #dbeafe; color: #1d4ed8; }
.status-2 { background: #dcfce7; color: #166534; }
.doc-id { font-size: 11px; color: var(--color-muted); font-family: monospace; }
.source-tag { font-size: 10px; padding: 1px 4px; background: var(--color-surface-soft); border-radius: 4px; color: var(--color-muted); }
.review-question { flex: 1; font-size: 14px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.review-score-preview { flex: none; font-size: 13px; }
.expand-btn { background: none; border: 1px solid var(--color-border); padding: 4px 10px; border-radius: var(--radius-sm); font-size: 12px; color: var(--color-muted); cursor: pointer; flex: none; }
.expand-btn:hover { color: var(--color-primary); border-color: var(--color-primary); }

/* 展开详情 */
.review-detail { padding: 0 16px 16px; border-top: 1px solid var(--color-border); }
.detail-section { margin-top: 12px; padding: 12px; background: var(--color-surface-soft); border-radius: var(--radius-sm); }
.detail-section.section-done { border-left: 3px solid var(--color-success); }
.section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.section-header h4 { margin: 0; font-size: 13px; }
.detail-section h4 { margin: 0 0 8px; font-size: 13px; }

/* chunks */
.chunk-list { display: flex; flex-direction: column; gap: 6px; max-height: 300px; overflow-y: auto; }
.chunk-item { background: #fff; border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 8px; }
.chunk-head { display: flex; gap: 8px; font-size: 11px; color: var(--color-muted); margin-bottom: 4px; }
.chunk-score { font-weight: 600; }
.chunk-path { font-size: 11px; color: var(--color-primary); margin-bottom: 4px; }
.chunk-text { font-size: 12px; white-space: pre-wrap; max-height: 100px; overflow-y: auto; color: var(--color-text); background: none; padding: 0; margin: 0; }

/* 答案框 */
.answer-box { font-size: 14px; line-height: 1.6; white-space: pre-wrap; padding: 8px; background: #fff; border-radius: var(--radius-sm); border: 1px solid var(--color-border); }
.answer-error { color: var(--color-danger); }
.answer-empty { color: var(--color-muted); font-size: 12px; }

/* 评分 */
.rating-area { display: flex; flex-direction: column; gap: 8px; }
.star-group { display: flex; align-items: center; gap: 4px; }
.star-btn { background: none; border: none; font-size: 20px; cursor: pointer; padding: 0 2px; line-height: 1; }
.star-btn.active { transform: scale(1.1); }
.rating-label { font-size: 13px; color: var(--color-muted); margin-left: 8px; }
.rating-comment { width: 100%; border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 6px 8px; font-size: 13px; resize: vertical; }
.rating-existing { font-size: 12px; color: var(--color-success); }

/* 参考答案编辑弹窗 */
.ref-edit-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.3); z-index: 40; display: flex; align-items: center; justify-content: center; }
.ref-edit-box { background: #fff; border-radius: var(--radius-md); padding: 20px; width: 600px; max-width: 90%; }
.ref-edit-box h4 { margin: 0 0 12px; }
.ref-edit-box textarea { width: 100%; border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 8px; font-size: 13px; }
.ref-edit-actions { display: flex; gap: 8px; margin-top: 12px; justify-content: flex-end; }

/* 元信息 */
.meta-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; font-size: 12px; }
.meta-label { color: var(--color-muted); display: block; }
.meta-val { font-family: monospace; font-size: 11px; }
.section-meta { background: transparent; border: 1px dashed var(--color-border); }

/* 答案三栏对比 */
.answer-comparison { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; margin-top: 12px; }
.answer-col { background: #fff; border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 12px; display: flex; flex-direction: column; min-height: 120px; }
.answer-col.col-highlight { border-color: var(--color-primary); border-width: 2px; }
.answer-col-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.answer-col-header h4 { margin: 0; font-size: 12px; white-space: nowrap; }
.col-meta { font-size: 10px; color: var(--color-muted); }
.answer-content { font-size: 13px; line-height: 1.7; white-space: pre-wrap; flex: 1; overflow-y: auto; max-height: 250px; }
.answer-error { color: var(--color-danger); }
.answer-placeholder { font-size: 12px; color: var(--color-muted); text-align: center; padding: 16px 8px; flex: 1; display: flex; flex-direction: column; justify-content: center; }
.answer-placeholder .hint { font-size: 11px; margin-top: 4px; opacity: 0.6; }

@media (max-width: 900px) {
  .answer-comparison { grid-template-columns: 1fr; }
}

/* 通用 */
.empty-state { text-align: center; padding: 60px 20px; color: var(--color-muted); }
.empty-hint { font-size: 13px; margin-top: 4px; }
.btn-link { background: none; border: none; color: var(--color-primary); cursor: pointer; font-size: 13px; padding: 2px 6px; }
.btn-link:hover { text-decoration: underline; }
.btn-xs { padding: 4px 10px; font-size: 12px; }
</style>
