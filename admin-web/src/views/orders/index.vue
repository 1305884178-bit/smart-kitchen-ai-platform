<template>
  <div class="orders-page">
    <h3 class="page-title">订单管理</h3>

    <!-- 筛选区 -->
    <el-card class="filter-card">
      <el-form :inline="true" :model="filterForm">
        <el-form-item label="订单状态">
          <el-select v-model="filterForm.status" placeholder="全部" clearable style="width:160px">
            <el-option label="已下单" :value="0" />
            <el-option label="已上菜" :value="10" />
            <el-option label="已结账" :value="20" />
            <el-option label="已撤销" :value="90" />
          </el-select>
        </el-form-item>
        <el-form-item label="座位号">
          <el-select v-model="filterForm.seatNumber" placeholder="全部" clearable style="width:160px">
            <el-option v-for="s in seatOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadOrders">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 订单表格 -->
    <el-card class="table-card">
      <el-table :data="tableData" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="orderNo" label="订单号" width="180" />
        <el-table-column prop="seatNumber" label="座位号" width="90" />
        <el-table-column label="菜品明细" min-width="200">
          <template #default="{ row }">
            <div v-for="d in row.details" :key="d.id" class="detail-line">
              {{ d.dishName }} x{{ d.quantity }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="100">
          <template #default="{ row }">&yen;{{ row.totalAmount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="下单时间" width="170" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <div class="action-buttons">
              <el-button size="small" @click="showDetail(row.id)">详情</el-button>
              <el-button
                v-if="row.status === 0 || row.status === 10"
                size="small" type="danger"
                @click="handleCancel(row.id)"
              >撤销</el-button>
              <el-button
                v-if="row.status === 0"
                size="small" type="success"
                @click="handleComplete(row.id)"
              >完成出餐</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadOrders"
          @current-change="loadOrders"
        />
      </div>
    </el-card>

    <!-- 订单详情弹窗 -->
    <el-dialog v-model="detailVisible" title="订单详情" width="560px">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="座位号">{{ detail.seatNumber }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ detail.totalAmount }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="下单时间" :span="2">{{ detail.createTime }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.payTime" label="支付时间" :span="2">{{ detail.payTime }}</el-descriptions-item>
        </el-descriptions>
        <h4 style="margin:16px 0 8px">菜品明细</h4>
        <el-table :data="detail.details" size="small">
          <el-table-column prop="dishName" label="菜品" />
          <el-table-column prop="quantity" label="数量" width="80" />
          <el-table-column prop="price" label="单价" width="100" />
        </el-table>
        <div v-if="detail.availableActions && detail.availableActions.length > 0" style="margin-top:16px">
          <h4>可用操作</h4>
          <el-tag v-for="action in detail.availableActions" :key="action" style="margin-right:6px">{{ action }}</el-tag>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'

const tableData = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

const filterForm = reactive({ status: null, seatNumber: null })
const seatOptions = ref([])

const detailVisible = ref(false)
const detail = ref(null)

// 状态映射
const statusLabel = (code) => {
  const map = { 0: '已下单', 10: '已上菜', 20: '已结账', 90: '已撤销' }
  return map[code] ?? '未知'
}
const statusTagType = (code) => {
  const map = { 0: 'warning', 10: 'success', 20: 'info', 90: 'danger' }
  return map[code] ?? 'info'
}

async function loadOrders() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (filterForm.status !== null && filterForm.status !== '') params.status = filterForm.status
    if (filterForm.seatNumber) params.seatNumber = filterForm.seatNumber
    const res = await request.get('/api/order/admin-list', { params })
    tableData.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    // 错误已在拦截器处理
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filterForm.status = null
  filterForm.seatNumber = null
  page.value = 1
  loadOrders()
}

/**
 * 加载可选座位号列表
 */
async function loadSeats() {
  try {
    const res = await request.get('/api/seat/available')
    seatOptions.value = (res.data || []).map(s => s.seatNumber)
  } catch (e) {
    // 错误已在拦截器处理
  }
}

async function showDetail(id) {
  try {
    const res = await request.get(`/api/order/admin-detail/${id}`)
    detail.value = res.data
    detailVisible.value = true
  } catch (e) {
    // 错误已在拦截器处理
  }
}

async function handleCancel(id) {
  try {
    await ElMessageBox.confirm('确定要撤销该订单吗？', '提示', { type: 'warning' })
    await request.post(`/api/order/${id}/cancel`)
    ElMessage.success('订单已撤销')
    loadOrders()
  } catch (e) {
    // 用户取消或错误
  }
}

async function handleComplete(id) {
  try {
    await request.post(`/api/order/${id}/complete`)
    ElMessage.success('出餐完成')
    loadOrders()
  } catch (e) {
    // 错误已在拦截器处理
  }
}

onMounted(() => {
  loadSeats()
  loadOrders()
})
</script>

<style scoped>
.page-title {
  font-size: 20px;
  margin-bottom: 16px;
  color: #303133;
}
.filter-card {
  margin-bottom: 16px;
}
.table-card {
  margin-bottom: 16px;
}
.detail-line {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}
.action-buttons {
  display: flex;
  gap: 4px;
  white-space: nowrap;
}
.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
