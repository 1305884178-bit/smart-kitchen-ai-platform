<template>
  <div class="dashboard">
    <h3 class="page-title">仪表盘</h3>
    <el-row :gutter="20" class="stats-row">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-icon" style="background:#e6f7ff">
              <el-icon :size="28" color="#1890ff"><Document /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-label">今日订单数</div>
              <div class="stat-value">{{ stats.todayOrderCount ?? '-' }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-icon" style="background:#f6ffed">
              <el-icon :size="28" color="#52c41a"><Money /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-label">今日营收</div>
              <div class="stat-value">&yen;{{ stats.todayRevenue ?? '-' }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-icon" style="background:#fff7e6">
              <el-icon :size="28" color="#fa8c16"><Clock /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-label">待出餐数量</div>
              <div class="stat-value">{{ stats.pendingServeCount ?? '-' }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-content">
            <div class="stat-icon" style="background:#fff1f0">
              <el-icon :size="28" color="#f5222d"><Warning /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-label">库存预警菜品数</div>
              <div class="stat-value">{{ stats.lowStockDishCount ?? '-' }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const stats = ref({})

async function loadStats() {
  try {
    const res = await request.get('/api/admin/dashboard/stats')
    stats.value = res.data
  } catch (e) {
    // 错误已在拦截器处理
  }
}

onMounted(() => {
  loadStats()
})
</script>

<style scoped>
.page-title {
  font-size: 20px;
  margin-bottom: 20px;
  color: #303133;
}

.stats-row {
  margin-bottom: 20px;
}

.stat-card {
  cursor: default;
}
.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;
}
.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-label {
  font-size: 14px;
  color: #909399;
  margin-bottom: 4px;
}
.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: #303133;
}
</style>
