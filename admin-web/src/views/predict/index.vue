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
    </el-card>

    <!-- 结果表格 -->
    <el-card class="result-card">
      <el-table :data="results" stripe v-loading="loadingResult">
        <el-table-column prop="dishName" label="菜品名称" width="140" />
        <el-table-column prop="baseQuantity" label="基准量" width="80" />
        <el-table-column prop="aiSuggestQuantity" label="AI 建议量" width="100" />
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
            <el-button size="small" type="primary" @click="openConfirm(row)">确认/覆盖</el-button>
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
import { ref, reactive } from 'vue'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const predictDate = ref('')
const triggering = ref(false)
const loadingResult = ref(false)
const results = ref([])

// 确认弹窗
const confirmVisible = ref(false)
const confirmDish = ref(null)
const confirmForm = reactive({ finalQuantity: 0 })
const confirming = ref(false)

async function triggerPredict() {
  if (!predictDate.value) { ElMessage.warning('请选择预测日期'); return }
  triggering.value = true
  try {
    await request.post('/api/admin/predict/trigger', { targetDate: predictDate.value })
    ElMessage.success('预测任务已触发')
  } catch (e) {}
  finally { triggering.value = false }
}

async function loadResult() {
  if (!predictDate.value) { ElMessage.warning('请选择预测日期'); return }
  loadingResult.value = true
  try {
    const res = await request.get('/api/admin/predict/result', {
      params: { targetDate: predictDate.value }
    })
    // Python 返回的数据可能在 data.results 或 data 中
    const data = res.data
    results.value = Array.isArray(data) ? data : (data?.results || data?.data || [])
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
.result-card {
  margin-bottom: 16px;
}
</style>
