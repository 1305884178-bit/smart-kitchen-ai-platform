<template>
  <div class="review-page">
    <h3 class="page-title">评价管理</h3>

    <!-- 筛选 -->
    <el-card class="filter-card">
      <el-form :inline="true">
        <el-form-item label="评分筛选">
          <el-select v-model="scoreFilter" placeholder="全部" clearable @change="loadReviews" style="width:140px">
            <el-option label="5 分" :value="5" />
            <el-option label="4 分" :value="4" />
            <el-option label="3 分" :value="3" />
            <el-option label="2 分" :value="2" />
            <el-option label="1 分" :value="1" />
          </el-select>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 评价表格 -->
    <el-card class="table-card">
      <el-table :data="reviews" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="orderId" label="订单ID" width="80" />
        <el-table-column label="评分" width="150">
          <template #default="{ row }">
            <el-rate :model-value="Number(row.score)" :max="5" disabled />
            <span class="score-text">{{ row.score }} 分</span>
          </template>
        </el-table-column>
        <el-table-column prop="comment" label="评价内容" min-width="260" />
        <el-table-column prop="createTime" label="评价时间" width="170" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'

const reviews = ref([])
const loading = ref(false)
const scoreFilter = ref(null)

async function loadReviews() {
  loading.value = true
  try {
    const params = {}
    if (scoreFilter.value) params.score = scoreFilter.value
    const res = await request.get('/api/admin/review/list', { params })
    reviews.value = res.data || []
  } catch (e) {}
  finally { loading.value = false }
}

onMounted(() => {
  loadReviews()
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
.score-text { margin-left: 8px; color: #606266; white-space: nowrap; }
</style>
