<template>
  <section class="eval-dataset">
    <!-- 顶部栏 -->
    <div class="top-bar">
      <h3>评估测试集管理</h3>
      <div class="top-actions">
        <button class="btn btn-primary btn-sm" :disabled="generating" @click="showAutoGenPanel = !showAutoGenPanel">
          {{ showAutoGenPanel ? '收起' : '⚙ 自动生成' }}
        </button>
        <button class="btn btn-primary btn-sm" @click="startAdd">＋ 新增</button>
        <button class="btn btn-outline btn-sm" @click="refresh">🔄 刷新</button>
      </div>
    </div>

    <!-- 自动生成面板 -->
    <div v-if="showAutoGenPanel" class="auto-gen-panel">
      <div class="gen-row">
        <label>文档 ID</label>
        <input v-model="genDocId" type="text" placeholder="输入文档 ID" class="input-sm" />
        <button class="btn btn-primary btn-sm" :disabled="!genDocId || generating" @click="autoGenerate">
          <span v-if="generating" class="spinner"></span>
          {{ generating ? '生成中...' : '开始生成' }}
        </button>
      </div>
      <p class="gen-hint">自动从文档画像的 example_questions 中捞取问题，并标注 ground truth chunks</p>
    </div>

    <!-- ──── 新增编辑面板 ──── -->
    <div v-if="editing" class="edit-panel">
      <h4>{{ isAdding ? '新增测试条目' : '编辑测试条目' }}</h4>
      <div class="edit-grid">
        <div class="edit-field">
          <label>文档 ID</label>
          <input v-model="form.documentId" type="number" class="input-full" placeholder="选填（不指定时仅算 Answer Accuracy）" />
        </div>
        <div class="edit-field">
          <label>难度</label>
          <select v-model="form.difficulty" class="input-full">
            <option value="easy">easy</option>
            <option value="medium">medium</option>
            <option value="hard">hard</option>
          </select>
        </div>
        <div class="edit-field">
          <label>激活</label>
          <select v-model.number="form.isActive" class="input-full">
            <option :value="1">是</option>
            <option :value="0">否</option>
          </select>
        </div>
        <div class="edit-field">
          <label>来源</label>
          <input v-model="form.source" class="input-full" disabled />
        </div>
      </div>
      <div class="edit-field">
        <label>问题 <span class="req">*</span></label>
        <textarea v-model="form.question" rows="2" class="input-full" placeholder="必填"></textarea>
      </div>
      <div class="edit-field">
        <label>参考答案（可选）</label>
        <textarea v-model="form.referenceAnswer" rows="3" class="input-full" placeholder="标准答案..."></textarea>
      </div>
      <div class="edit-field">
        <label>Ground Truth Chunk IDs（JSON 数组，可选）</label>
        <input v-model="form.groundTruthChunkIds" class="input-full" placeholder='[1, 2, 3]' />
      </div>
      <div class="edit-actions">
        <button class="btn btn-primary btn-sm" :disabled="saving" @click="saveEdit">
          {{ saving ? '保存中...' : '保存' }}
        </button>
        <button class="btn btn-outline btn-sm" @click="cancelEdit">取消</button>
        <span v-if="formError" class="edit-error">{{ formError }}</span>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid" v-if="items.length">
      <div class="stat-card">
        <span class="stat-value">{{ items.length }}</span>
        <span class="stat-label">总问题数</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ uniqueDocs }}</span>
        <span class="stat-label">覆盖文档数</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ filteredItems.filter(i => i.isActive).length }}</span>
        <span class="stat-label">激活中</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ filteredItems.filter(i => i.referenceAnswer).length }}</span>
        <span class="stat-label">已有参考答案</span>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar" v-if="items.length">
      <div class="filter-group">
        <label>文档 ID</label>
        <input v-model="filterDocId" placeholder="筛选" class="input-xs" />
      </div>
      <div class="filter-group">
        <label>状态</label>
        <select v-model="filterActive" class="input-xs">
          <option value="all">全部</option>
          <option value="active">激活</option>
          <option value="inactive">停用</option>
        </select>
      </div>
      <span class="filter-count">{{ filteredItems.length }} / {{ items.length }}</span>
    </div>

    <!-- 列表 -->
    <table class="data-table" v-if="filteredItems.length">
      <thead>
        <tr>
          <th style="width:40px;">#</th>
          <th style="width:100px;">文档 ID</th>
          <th>问题</th>
          <th style="width:60px;">来源</th>
          <th style="width:60px;">难度</th>
          <th style="width:50px;">激活</th>
          <th style="width:130px;">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(item, idx) in filteredItems"
          :key="item.id"
          :class="{ 'row-inactive': !item.isActive }"
        >
          <td>{{ idx + 1 }}</td>
          <td class="cell-mono">{{ item.documentId }}</td>
          <td class="cell-question" :title="item.question">{{ item.question }}</td>
          <td><code class="tag">{{ item.source }}</code></td>
          <td><span class="diff-badge" :class="`diff-${item.difficulty}`">{{ item.difficulty }}</span></td>
          <td>{{ item.isActive ? '✅' : '—' }}</td>
          <td class="cell-actions">
            <button class="btn-link" @click="startEdit(item)">编辑</button>
            <button class="btn-link link-danger" @click="confirmDelete(item)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-else class="empty-state">
      <p v-if="items.length && filteredItems.length === 0">没有匹配当前筛选条件的条目</p>
      <p v-else>暂无测试数据</p>
      <p class="empty-hint">可「自动生成」从文档画像捞取问题，或点击「＋ 新增」手工录入</p>
    </div>

    <!-- 删除确认弹窗 -->
    <transition name="modal-fade">
      <div v-if="deleteTarget" class="modal-overlay" @click.self="deleteTarget = null">
        <div class="modal-card">
          <h4>确认删除</h4>
          <p class="delete-question">{{ deleteTarget.question }}</p>
          <p class="delete-hint">此操作不可恢复，删除后评估运行将不再包含此题。</p>
          <div class="modal-actions">
            <button class="btn btn-danger btn-sm" :disabled="deleting" @click="doDelete">
              {{ deleting ? '删除中...' : '确认删除' }}
            </button>
            <button class="btn btn-outline btn-sm" @click="deleteTarget = null">取消</button>
          </div>
        </div>
      </div>
    </transition>
  </section>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { evalApi } from '../../api/api'

const items = ref([])
const showAutoGenPanel = ref(false)
const genDocId = ref('')
const generating = ref(false)

const filterDocId = ref('')
const filterActive = ref('all')

// 表单状态
const editing = ref(false)
const isAdding = ref(false)
const form = ref({})
const saving = ref(false)
const formError = ref('')

const deleteTarget = ref(null)
const deleting = ref(false)

const uniqueDocs = computed(() => new Set(items.value.map(i => i.documentId)).size)

const filteredItems = computed(() => {
  let list = items.value
  if (filterDocId.value) {
    list = list.filter(i => String(i.documentId).includes(filterDocId.value))
  }
  if (filterActive.value === 'active') list = list.filter(i => i.isActive)
  else if (filterActive.value === 'inactive') list = list.filter(i => !i.isActive)
  return list
})

function resetForm() {
  form.value = {
    id: null,
    documentId: null,
    question: '',
    referenceAnswer: '',
    difficulty: 'medium',
    isActive: 1,
    source: 'manual',
    groundTruthChunkIds: ''
  }
  formError.value = ''
}

function startAdd() {
  isAdding.value = true
  resetForm()
  editing.value = true
}

function startEdit(item) {
  isAdding.value = false
  form.value = {
    id: item.id,
    documentId: item.documentId,
    question: item.question,
    referenceAnswer: item.referenceAnswer || '',
    difficulty: item.difficulty || 'medium',
    isActive: item.isActive,
    source: item.source || 'manual',
    groundTruthChunkIds: item.groundTruthChunkIds || ''
  }
  formError.value = ''
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

async function saveEdit() {
  const f = form.value
  if (!f.question?.trim()) {
    formError.value = '问题不能为空'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    await evalApi.saveDataset({
      id: f.id || undefined,
      documentId: f.documentId ? Number(f.documentId) : undefined,
      question: f.question.trim(),
      referenceAnswer: f.referenceAnswer || undefined,
      difficulty: f.difficulty,
      isActive: f.isActive,
      groundTruthChunkIds: f.groundTruthChunkIds || undefined
    })
    editing.value = false
    await refresh()
  } catch (e) {
    formError.value = '保存失败: ' + e.message
  }
  saving.value = false
}

async function refresh() {
  try { items.value = await evalApi.listDataset() || [] } catch (e) { console.error(e) }
}

async function autoGenerate() {
  if (!genDocId.value) return
  generating.value = true
  try {
    const result = await evalApi.generateDataset(Number(genDocId.value))
    alert(result?.generated > 0 ? `已生成 ${result.generated} 条` : '未生成新数据')
    await refresh()
  } catch (e) {
    alert('生成失败: ' + e.message)
  }
  generating.value = false
}

function confirmDelete(item) {
  deleteTarget.value = item
}

async function doDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await evalApi.deleteDatasetItem(deleteTarget.value.id)
    items.value = items.value.filter(i => i.id !== deleteTarget.value.id)
    deleteTarget.value = null
  } catch (e) {
    alert('删除失败: ' + e.message)
  }
  deleting.value = false
}

onMounted(refresh)
</script>

<style scoped>
.eval-dataset { display: flex; flex-direction: column; gap: 12px; }
.top-bar { display: flex; justify-content: space-between; align-items: center; }
.top-bar h3 { margin: 0; }
.top-actions { display: flex; gap: 6px; }

.auto-gen-panel { background: #fff; border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 12px 16px; }
.gen-row { display: flex; align-items: center; gap: 8px; }
.gen-row label { font-size: 13px; color: var(--color-muted); white-space: nowrap; }
.gen-hint { font-size: 11px; color: var(--color-muted); margin-top: 6px; }
.input-sm { padding: 4px 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: 13px; width: 200px; }
.input-xs { padding: 4px 6px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: 12px; }
.input-full { width: 100%; padding: 6px 8px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-size: 13px; box-sizing: border-box; }

/* 编辑面板 */
.edit-panel { background: #fff; border: 2px solid var(--color-primary); border-radius: var(--radius-md); padding: 16px 20px; }
.edit-panel h4 { margin: 0 0 12px; font-size: 15px; }
.edit-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 10px; }
.edit-field { margin-bottom: 10px; }
.edit-field label { display: block; font-size: 12px; color: var(--color-muted); margin-bottom: 3px; font-weight: 500; }
.edit-field .req { color: var(--color-danger); }
.edit-actions { display: flex; align-items: center; gap: 8px; margin-top: 4px; }
.edit-error { font-size: 12px; color: var(--color-danger); }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat-card { background: #fff; border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 16px; text-align: center; }
.stat-value { display: block; font-size: 24px; font-weight: 700; color: var(--color-text); }
.stat-label { font-size: 12px; color: var(--color-muted); margin-top: 2px; }

.filter-bar { display: flex; align-items: center; gap: 12px; background: #fff; padding: 8px 12px; border-radius: var(--radius-md); border: 1px solid var(--color-border); }
.filter-group { display: flex; align-items: center; gap: 4px; }
.filter-group label { font-size: 12px; color: var(--color-muted); white-space: nowrap; }
.filter-count { font-size: 12px; color: var(--color-muted); margin-left: auto; }

.data-table { width: 100%; border-collapse: collapse; background: #fff; border-radius: var(--radius-md); overflow: hidden; border: 1px solid var(--color-border); }
.data-table th, .data-table td { padding: 8px 10px; text-align: left; border-bottom: 1px solid var(--color-border); font-size: 13px; }
.data-table th { font-weight: 600; color: var(--color-muted); background: var(--color-surface-soft); }
.row-inactive td { opacity: 0.5; }
.cell-mono { font-family: monospace; font-size: 12px; }
.cell-question { max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tag { background: var(--color-surface-soft); padding: 2px 6px; border-radius: 4px; font-size: 11px; }
.diff-badge { font-size: 11px; padding: 2px 8px; border-radius: 10px; }
.diff-easy { background: #dcfce7; color: #166534; }
.diff-medium { background: #fef3c7; color: #92400e; }
.diff-hard { background: #fee2e2; color: #991b1b; }
.btn-link { background: none; border: none; color: var(--color-primary); cursor: pointer; font-size: 12px; padding: 2px 6px; }
.btn-link:hover { text-decoration: underline; }
.link-danger { color: var(--color-danger); }
.cell-actions { white-space: nowrap; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 30; display: flex; align-items: center; justify-content: center; }
.modal-card { background: #fff; border-radius: var(--radius-md); padding: 24px; width: 480px; max-width: 90%; }
.modal-card h4 { margin: 0 0 8px; }
.delete-question { font-size: 14px; padding: 8px 12px; background: var(--color-surface-soft); border-radius: var(--radius-sm); }
.delete-hint { font-size: 12px; color: var(--color-muted); margin: 8px 0; }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 12px; }

.empty-state { text-align: center; padding: 60px 20px; color: var(--color-muted); }
.empty-hint { font-size: 13px; margin-top: 4px; }

.modal-fade-enter-active, .modal-fade-leave-active { transition: opacity 0.15s ease; }
.modal-fade-enter-from, .modal-fade-leave-to { opacity: 0; }
</style>
