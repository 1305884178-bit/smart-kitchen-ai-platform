<template>
  <div class="stock-page">
    <h3 class="page-title">库存管理</h3>

    <!-- 菜品库存列表 -->
    <el-card class="table-card">
      <el-table :data="dishList" stripe v-loading="loading" @row-click="selectDish" highlight-current-row>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="菜品名称" width="160" />
        <el-table-column label="当前库存" width="100">
          <template #default="{ row }">
            <span :style="{ color: row.dailyStock <= row.alertThreshold ? '#f56c6c' : '#303133' }">
              {{ row.dailyStock }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="alertThreshold" label="预警阈值" width="90" />
        <el-table-column label="库存状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.dailyStock === 0" type="danger" size="small">库存不足</el-tag>
            <el-tag v-else-if="row.dailyStock < row.alertThreshold" type="warning" size="small">预警</el-tag>
            <el-tag v-else type="success" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click.stop="openAdjustDialog(row)">调整库存</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 库存流水日志 -->
    <el-card v-if="stockDetail" class="detail-card">
      <div class="detail-header">
        <h4>{{ stockDetail.dishName }} — 变更流水</h4>
        <span>当前库存：{{ stockDetail.dailyStock }} | 预警阈值：{{ stockDetail.alertThreshold }}</span>
      </div>
      <el-table :data="stockDetail.logs" size="small" stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="changeType" label="变更类型" width="100" />
        <el-table-column prop="changeQty" label="变更数量" width="90" />
        <el-table-column prop="beforeQty" label="变更前" width="80" />
        <el-table-column prop="afterQty" label="变更后" width="80" />
        <el-table-column prop="orderNo" label="关联订单" width="170" />
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>
    </el-card>

    <!-- 库存调整弹窗 -->
    <el-dialog v-model="adjustVisible" title="调整库存" width="400px">
      <el-form :model="adjustForm">
        <el-form-item label="菜品">{{ adjustDish?.name }}</el-form-item>
        <el-form-item label="变更数量">
          <el-input-number v-model="adjustForm.changeQty" :min="-999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjustSubmitting" @click="submitAdjust">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const dishList = ref([])
const loading = ref(false)
const stockDetail = ref(null)

// 调整弹窗
const adjustVisible = ref(false)
const adjustDish = ref(null)
const adjustForm = reactive({ changeQty: 0 })
const adjustSubmitting = ref(false)

async function loadDishList() {
  loading.value = true
  try {
    const res = await request.get('/api/dish/list')
    dishList.value = res.data || []
  } catch (e) {}
  finally { loading.value = false }
}

async function selectDish(row) {
  try {
    const res = await request.get(`/api/admin/stock/view/${row.id}`)
    stockDetail.value = res.data
  } catch (e) {}
}

function openAdjustDialog(row) {
  adjustDish.value = row
  adjustForm.changeQty = 0
  adjustVisible.value = true
}

async function submitAdjust() {
  adjustSubmitting.value = true
  try {
    await request.put(`/api/admin/stock/update/${adjustDish.value.id}`, null, {
      params: { changeQty: adjustForm.changeQty }
    })
    ElMessage.success('库存调整成功')
    adjustVisible.value = false
    loadDishList()
    if (stockDetail.value && stockDetail.value.dishId === adjustDish.value.id) {
      selectDish(adjustDish.value)
    }
  } catch (e) {}
  finally { adjustSubmitting.value = false }
}

onMounted(() => {
  loadDishList()
})
</script>

<style scoped>
.page-title {
  font-size: 20px;
  margin-bottom: 16px;
  color: #303133;
}
.table-card {
  margin-bottom: 16px;
}
.detail-card {
  margin-bottom: 16px;
}
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  color: #606266;
}
.detail-header h4 {
  font-size: 16px;
  color: #303133;
}
</style>
