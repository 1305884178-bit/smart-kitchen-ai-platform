<template>
  <div class="knowledge-page">
    <h3 class="page-title">AI 知识库</h3>

    <!-- 上传区 -->
    <el-card class="upload-card">
      <el-form :model="uploadForm" label-width="100px">
        <el-form-item label="标题">
          <el-input v-model="uploadForm.title" placeholder="请输入文档标题" />
        </el-form-item>
        <el-form-item label="上传文件">
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            accept=".docx,.pdf"
            :on-change="handleFileChange"
          >
            <el-button>选择 Word/PDF 解析</el-button>
          </el-upload>
          <div style="color: #999; font-size: 12px; margin-top: 4px;">选择 .docx 或 .pdf 文件后自动解析内容到「文档内容」框</div>
        </el-form-item>
        <el-form-item label="文档内容">
          <el-input v-model="uploadForm.content" type="textarea" :rows="5" placeholder="输入要上传的知识文档内容" />
        </el-form-item>
        <el-form-item label="版本号">
          <el-input v-model="uploadForm.version" style="width:200px" placeholder="如 v1.0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="uploadForm.status" style="width:200px">
            <el-option label="草稿" value="draft" />
            <el-option label="已发布" value="active" />
            <el-option label="已归档" value="archived" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="uploadForm.effectiveFrom" type="date" placeholder="选择日期" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="uploading" @click="handleUpload">上传文档</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 文档列表 -->
    <el-card class="list-card">
      <el-table :data="documents" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="标题" min-width="180" />
        <el-table-column prop="version" label="版本号" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'active'" type="success" size="small">已发布</el-tag>
            <el-tag v-else-if="row.status === 'draft'" type="info" size="small">草稿</el-tag>
            <el-tag v-else-if="row.status === 'archived'" type="warning" size="small">已归档</el-tag>
            <el-tag v-else size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="分块数" width="80" />
        <el-table-column prop="createTime" label="创建时间" width="170" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const documents = ref([])
const loading = ref(false)
const uploading = ref(false)

const uploadForm = reactive({
  title: '',
  content: '',
  version: '',
  status: 'draft',
  effectiveFrom: '',
})

async function loadDocuments() {
  loading.value = true
  try {
    const res = await request.get('/api/admin/knowledge/list')
    documents.value = res.data || []
  } catch (e) {}
  finally { loading.value = false }
}

async function handleFileChange(file) {
  const formData = new FormData()
  formData.append('file', file.raw)
  try {
    const res = await request.post('/api/admin/knowledge/parse-file', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    uploadForm.content = res.data
    ElMessage.success('文件解析成功，已填入文档内容')
  } catch (e) {}
}

async function handleUpload() {
  if (!uploadForm.content.trim()) { ElMessage.warning('请输入文档内容'); return }
  uploading.value = true
  try {
    await request.post('/api/admin/knowledge/upload', {
      title: uploadForm.title,
      content: uploadForm.content,
      version: uploadForm.version,
      status: uploadForm.status,
      effectiveFrom: uploadForm.effectiveFrom || null,
      metadata: {},
    })
    ElMessage.success('文档上传成功')
    uploadForm.title = ''
    uploadForm.content = ''
    uploadForm.version = ''
    loadDocuments()
  } catch (e) {}
  finally { uploading.value = false }
}

onMounted(() => {
  loadDocuments()
})
</script>

<style scoped>
.page-title {
  font-size: 20px;
  margin-bottom: 16px;
  color: #303133;
}
.upload-card {
  margin-bottom: 16px;
}
.list-card {
  margin-bottom: 16px;
}
</style>
