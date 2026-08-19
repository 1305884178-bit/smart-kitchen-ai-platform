<template>
  <div class="predict-page">
    <h3 class="page-title">AI 备菜预测</h3>

    <!-- 触发区 -->
    <el-card class="trigger-card">
      <el-form :inline="true">
        <el-form-item label="预测日期">
          <el-date-picker v-model="predictDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="triggering" @click="triggerPredict">触发预测</el-button>
          <el-button :loading="loadingResult" @click="loadResult">查询结果</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="predicting"
        :title="predictProgress || '预测任务执行中…'"
        type="info"
        show-icon
        :closable="false"
        class="predict-tip"
      />
    </el-card>

    <!-- 结果表格 -->
    <el-card class="result-card">
      <el-table :data="results" stripe v-loading="loadingResult">
        <el-table-column prop="dishName" label="菜品名称" width="140" />
        <el-table-column prop="baseQuantity" label="基准量" width="80" />
        <el-table-column prop="aiSuggestQuantity" label="AI 建议量" width="100" />
        <el-table-column label="最终确认量" width="110">
          <template #default="{ row }">
            <span v-if="row.status === 1" class="final-quantity">{{ row.finalQuantity ?? row.aiSuggestQuantity }}</span>
            <span v-else class="final-quantity-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template #default="{ row }">
            <span :style="{ color: row.confidence < 0.4 ? '#f56c6c' : '#67c23a' }">
              {{ (row.confidence * 100).toFixed(0) }}%
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="reasoning" label="推理说明" min-width="200" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status !== 1" size="small" type="primary" @click="openConfirm(row)">确认/覆盖</el-button>
            <el-tag v-else type="success" size="small">操作成功</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 确认/覆盖弹窗 -->
    <el-dialog v-model="confirmVisible" title="确认预测量" width="400px">
      <el-form :model="confirmForm">
        <el-form-item label="菜品">{{ confirmDish?.dishName }}</el-form-item>
        <el-form-item label="AI 建议量">{{ confirmDish?.aiSuggestQuantity }}</el-form-item>
        <el-form-item label="最终数量">
          <el-input-number v-model="confirmForm.finalQuantity" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="confirming" @click="submitConfirm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onUnmounted } from 'vue'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const predictDate = ref('')
const triggering = ref(false)
const loadingResult = ref(false)
const results = ref([])

// 预测进度
const predicting = ref(false)
const predictProgress = ref('')
let pollTimer = null

// 确认弹窗
const confirmVisible = ref(false)
const confirmDish = ref(null)
const confirmForm = reactive({ finalQuantity: 0 })
const confirming = ref(false)

async function triggerPredict() {
  if (!predictDate.value) { ElMessage.warning('请选择预测日期'); return }
  triggering.value = true
  try {
    const res = await request.post('/api/admin/predict/trigger', { targetDate: predictDate.value })
    const taskId = res.data?.task_id
    if (!taskId) {
      ElMessage.warning('未获取到任务ID，请稍后手动查询结果')
      return
    }
    ElMessage.success('预测任务已触发，正在自动刷新结果…')
    startPolling(taskId)
  } catch (e) {}
  finally { triggering.value = false }
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling(taskId) {
  stopPolling()
  predicting.value = true
  predictProgress.value = '预测任务执行中，正在等待结果…'

  let elapsed = 0
  const INTERVAL_MS = 3000
  const MAX_POLL_MS = 120000

  pollTimer = setInterval(async () => {
    if (!pollTimer) return
    elapsed += INTERVAL_MS
    if (elapsed > MAX_POLL_MS) {
      stopPolling()
      predicting.value = false
      ElMessage.warning('预测耗时较长，请稍后手动点击“查询结果”查看')
      return
    }
    try {
      const statusRes = await request.get('/api/admin/predict/status', { params: { taskId } })
      const status = statusRes.data?.data || statusRes.data
      if (!status) return

      if (status.status === 'running') {
        predictProgress.value = status.done !== undefined && status.total
          ? `预测中：已完成 ${status.done}/${status.total} 道菜品`
          : (status.message || '预测任务执行中…')
        return
      }

      stopPolling()
      if (status.status === 'success') {
        ElMessage.success(status.message || '预测完成')
      } else {
        ElMessage.warning(status.message || '预测结束，部分菜品可能失败')
      }
      await loadResult()
      predicting.value = false
    } catch (e) {
      // 状态查询失败时继续轮询，避免中断体验
    }
  }, INTERVAL_MS)
}

onUnmounted(stopPolling)

async function loadResult() {
  if (!predictDate.value) { ElMessage.warning('请选择预测日期'); return }
  loadingResult.value = true
  try {
    const res = await request.get('/api/admin/predict/result', {
      params: { targetDate: predictDate.value }
    })
    // Python 返回的数据可能在 data.results 或 data 中
    const data = res.data
    const list = Array.isArray(data) ? data : (data?.results || data?.data || [])
    // Python 返回 snake_case 字段，映射为前端表格使用的 camelCase
    results.value = list.map(item => ({
      ...item,
      recordId: item.id,
      dishName: item.dish_name,
      baseQuantity: item.base_quantity,
      aiSuggestQuantity: item.ai_suggest_quantity,
      finalQuantity: item.final_quantity,
    }))
  } catch (e) {}
  finally { loadingResult.value = false }
}

function openConfirm(row) {
  confirmDish.value = row
  confirmForm.finalQuantity = row.aiSuggestQuantity || row.baseQuantity || 0
  confirmVisible.value = true
}

async function submitConfirm() {
  confirming.value = true
  try {
    const payload = {
      recordId: confirmDish.value.recordId || confirmDish.value.id,
      finalQuantity: confirmForm.finalQuantity,
      confirmedBy: Number(localStorage.getItem('userId') || '0'),
    }
    await request.post('/api/admin/predict/confirm', payload)
    ElMessage.success('确认成功')
    confirmVisible.value = false
    // 立即更新该行状态与最终确认量，表格即时刷新，无需等待重新查询
    const row = results.value.find(r => (r.recordId || r.id) === payload.recordId)
    if (row) {
      row.status = 1
      row.finalQuantity = confirmForm.finalQuantity
    }
    loadResult()
  } catch (e) {}
  finally { confirming.value = false }
}
</script>

<style scoped>
.page-title {
  font-size: 20px;
  margin-bottom: 16px;
  color: #303133;
}
.trigger-card {
  margin-bottom: 16px;
}
.predict-tip {
  margin-top: 12px;
}
.result-card {
  margin-bottom: 16px;
}
.final-quantity {
  color: #67c23a;
  font-weight: 600;
}
.final-quantity-empty {
  color: #c0c4cc;
}
</style>
