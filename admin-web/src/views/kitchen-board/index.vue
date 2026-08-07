<template>
  <div class="kitchen-board">
    <div class="page-header">
      <h3 class="page-title">厨房看板</h3>
      <el-tag v-if="wsConnected" type="success">实时连接中</el-tag>
      <el-tag v-else type="danger">连接断开</el-tag>
    </div>
    <el-empty v-if="orders.length === 0" description="暂无待出餐订单" />
    <el-row v-else :gutter="16">
      <el-col v-for="order in orders" :key="order.id" :span="8" style="margin-bottom:16px">
        <el-card shadow="hover" class="order-card">
          <template #header>
            <div class="card-header">
              <span>#{{ order.orderNo }}</span>
              <el-tag type="warning" size="small">{{ order.seatNumber }}</el-tag>
            </div>
          </template>
          <div class="card-body">
            <div v-for="d in order.details" :key="d.id" class="dish-item">
              <span>{{ d.dishName }}</span>
              <span>x{{ d.quantity }}</span>
            </div>
            <div class="card-time">下单时间：{{ order.createTime }}</div>
          </div>
          <div class="card-footer">
            <el-button type="primary" size="small" @click="handleServe(order.id)">
              完成出餐
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import request from '@/utils/request'
import { createWebSocket } from '@/utils/websocket'
import { ElMessage } from 'element-plus'

const orders = ref([])
const wsConnected = ref(false)
let wsClient = null

// 获取 HTTP 快照
async function getSnapshot() {
  const res = await request.get('/api/kitchen-board/orders')
  orders.value = res.data || []
}

// 完成出餐
async function handleServe(orderId) {
  try {
    await request.post(`/api/kitchen-board/order/${orderId}/serve`)
    ElMessage.success('出餐完成')
  } catch (e) {
    // 错误已在拦截器处理
  }
}

// 处理 WebSocket 消息
function handleWsMessage(data) {
  if (data.type === 'NEW_ORDER') {
    orders.value.unshift(data.order)
  } else if (data.type === 'ORDER_SERVED' || data.type === 'ORDER_PAID' || data.type === 'ORDER_CANCELLED') {
    orders.value = orders.value.filter(o => o.id !== data.orderId)
  }
}

onMounted(() => {
  getSnapshot()
  wsClient = createWebSocket('/ws/kitchen-board', {
    onMessage: handleWsMessage,
    onOpen: () => { wsConnected.value = true },
    onClose: () => { wsConnected.value = false },
    getSnapshot,
  })
})

onUnmounted(() => {
  if (wsClient) wsClient.close()
})
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}
.page-title {
  font-size: 20px;
  color: #303133;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.dish-item {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 14px;
  color: #606266;
}
.card-time {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}
.card-footer {
  margin-top: 12px;
  text-align: right;
}
</style>
