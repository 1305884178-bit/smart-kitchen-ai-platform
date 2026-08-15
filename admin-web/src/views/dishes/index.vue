<template>
  <div class="dishes-page">
    <div class="page-header">
      <h3 class="page-title">菜品管理</h3>
      <el-button type="primary" @click="openDishDialog()">新增菜品</el-button>
    </div>

    <!-- 分类 Tab -->
    <el-card class="category-card">
      <div class="category-header">
        <span>分类筛选：</span>
        <el-radio-group v-model="selectedCategoryId" @change="loadDishes">
          <el-radio-button :value="null">全部</el-radio-button>
          <el-radio-button v-for="cat in categories" :key="cat.id" :value="cat.id">{{ cat.name }}</el-radio-button>
        </el-radio-group>
        <el-button size="small" @click="openCategoryDialog()" style="margin-left:16px">管理分类</el-button>
      </div>
    </el-card>

    <!-- 菜品表格 -->
    <el-card class="table-card">
      <el-table :data="dishes" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column label="图片" width="90">
          <template #default="{ row }">
            <el-image v-if="row.image" :src="row.image" style="width:60px;height:60px" fit="cover" />
            <span v-else class="no-image">无图</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="菜品名称" width="140" />
        <el-table-column label="分类" width="100">
          <template #default="{ row }">{{ catName(row.categoryId) }}</template>
        </el-table-column>
        <el-table-column label="价格" width="100">
          <template #default="{ row }">&yen;{{ row.price }}</template>
        </el-table-column>
        <el-table-column prop="dailyStock" label="日库存" width="80" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              @change="(val) => handleToggleStatus(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="配料" min-width="140">
          <template #default="{ row }">{{ formatIngredients(row.ingredients) }}</template>
        </el-table-column>
        <el-table-column label="过敏原" min-width="120">
          <template #default="{ row }">{{ formatIngredients(row.allergens) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDishDialog(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 菜品表单弹窗 -->
    <el-dialog v-model="dishDialogVisible" :title="isEdit ? '编辑菜品' : '新增菜品'" width="560px">
      <el-form ref="dishFormRef" :model="dishForm" :rules="dishRules" label-width="80px">
        <el-form-item label="菜品名称" prop="name">
          <el-input v-model="dishForm.name" />
        </el-form-item>
        <el-form-item label="分类" prop="categoryId">
          <el-select v-model="dishForm.categoryId" placeholder="请选择分类">
            <el-option v-for="cat in categories" :key="cat.id" :label="cat.name" :value="cat.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="价格" prop="price">
          <el-input-number v-model="dishForm.price" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="日库存" prop="dailyStock">
          <el-input-number v-model="dishForm.dailyStock" :min="0" />
        </el-form-item>
        <el-form-item label="预警阈值" prop="alertThreshold">
          <el-input-number v-model="dishForm.alertThreshold" :min="0" />
        </el-form-item>
        <el-form-item label="配料" prop="ingredients">
          <el-input v-model="dishForm.ingredients" type="textarea" :rows="3" placeholder="用逗号或顿号分隔，如：猪肉、大葱、鸡蛋" />
        </el-form-item>
        <el-form-item label="过敏原" prop="allergens">
          <el-input v-model="dishForm.allergens" type="textarea" :rows="2" placeholder="用逗号或顿号分隔，如：鸡蛋、小麦、大豆" />
        </el-form-item>
        <el-form-item label="新品初始库存" prop="newProductInitialStock">
          <el-input-number v-model="dishForm.newProductInitialStock" :min="0" />
        </el-form-item>
        <el-form-item label="上架状态">
          <el-switch
            v-model="dishForm.status"
            :active-value="1"
            :inactive-value="0"
          />
        </el-form-item>
        <el-form-item label="菜品图片">
          <el-upload
            :http-request="ossUpload"
            :before-upload="beforeOssUpload"
            :on-success="onUploadSuccess"
            :on-error="onUploadError"
            list-type="picture-card"
            :limit="1"
            :file-list="uploadFileList"
          >
            <el-icon><Plus /></el-icon>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dishDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dishSubmitting" @click="submitDish">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分类管理弹窗 -->
    <el-dialog v-model="catDialogVisible" title="分类管理" width="480px">
      <div style="margin-bottom:16px">
        <el-input v-model="newCatName" placeholder="新分类名称" style="width:200px;margin-right:8px" />
        <el-input-number v-model="newCatSort" :min="0" placeholder="排序" style="width:100px;margin-right:8px" />
        <el-button type="primary" @click="addCategory">添加</el-button>
      </div>
      <el-table :data="categories" size="small">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="sort" label="排序" width="80" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" type="danger" @click="handleDeleteCategory(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import request from '@/utils/request'
import { uploadToOss } from '@/utils/oss'
import { ElMessage, ElMessageBox } from 'element-plus'

const dishes = ref([])
const categories = ref([])
const loading = ref(false)
const selectedCategoryId = ref(null)

// 分类管理
const catDialogVisible = ref(false)
const newCatName = ref('')
const newCatSort = ref(0)

// 菜品表单
const dishDialogVisible = ref(false)
const isEdit = ref(false)
const dishFormRef = ref(null)
const dishSubmitting = ref(false)
const dishForm = reactive({
  id: null,
  name: '',
  categoryId: null,
  price: 0,
  dailyStock: 0,
  alertThreshold: 0,
  ingredients: '',
  allergens: '',
  newProductInitialStock: 0,
  status: 1,
  image: '',
})

const dishRules = {
  name: [{ required: true, message: '请输入菜品名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
}

const uploadFileList = ref([])

function beforeOssUpload(file) {
  const isImage = file.type.startsWith('image/')
  const isLt2M = file.size / 1024 / 1024 < 2
  if (!isImage) {
    ElMessage.error('只能上传图片文件')
    return false
  }
  if (!isLt2M) {
    ElMessage.error('图片大小不能超过 2MB')
    return false
  }
  return true
}

async function ossUpload(options) {
  const { file, onSuccess, onError } = options
  try {
    const url = await uploadToOss(file)
    dishForm.image = url
    onSuccess({ url })
  } catch (err) {
    ElMessage.error('图片上传失败：' + err.message)
    onError(err)
  }
}

function onUploadSuccess() {}
function onUploadError() {
  ElMessage.warning('图片上传失败')
}

// 列表文本转 JSON 数组字符串（提交时用）
function listTextToJson(text) {
  const arr = String(text || '').split(/[,，、]/).map(s => s.trim()).filter(Boolean)
  return JSON.stringify(arr)
}

// JSON 数组字符串转列表文本（展示/编辑时用）
function jsonToListText(jsonStr) {
  if (!jsonStr) return ''
  try {
    const arr = JSON.parse(jsonStr)
    if (Array.isArray(arr)) return arr.join('、')
  } catch (e) {}
  return jsonStr
}

// 配料/过敏原格式化
function formatIngredients(value) {
  const text = jsonToListText(value)
  if (!text || text === '[]' || text === 'null') return '[无]'
  return text
}

// 分类名称映射
function catName(categoryId) {
  const cat = categories.value.find(c => c.id === categoryId)
  return cat ? cat.name : ''
}

// 加载分类
async function loadCategories() {
  try {
    const res = await request.get('/api/admin/dish/category/list')
    categories.value = res.data || []
  } catch (e) {}
}

// 加载菜品
async function loadDishes() {
  loading.value = true
  try {
    const params = {}
    if (selectedCategoryId.value) params.categoryId = selectedCategoryId.value
    const res = await request.get('/api/admin/dish/list', { params })
    dishes.value = res.data || []
  } catch (e) {}
  finally { loading.value = false }
}

// 打开菜品表单
function openDishDialog(row) {
  if (row) {
    isEdit.value = true
    Object.assign(dishForm, {
      id: row.id,
      name: row.name,
      categoryId: row.categoryId,
      price: row.price,
      dailyStock: row.dailyStock,
      alertThreshold: row.alertThreshold,
      ingredients: jsonToListText(row.ingredients),
      allergens: jsonToListText(row.allergens),
      newProductInitialStock: row.newProductInitialStock || 0,
      status: row.status,
      image: row.image || '',
    })
    uploadFileList.value = row.image ? [{ name: 'current', url: row.image }] : []
  } else {
    isEdit.value = false
    Object.assign(dishForm, {
      id: null, name: '', categoryId: null, price: 0,
      dailyStock: 0, alertThreshold: 0, ingredients: '', allergens: '',
      newProductInitialStock: 0, status: 1, image: '',
    })
    uploadFileList.value = []
    dishFormRef.value?.resetFields()
  }
  dishDialogVisible.value = true
}

// 提交菜品
async function submitDish() {
  const valid = await dishFormRef.value.validate().catch(() => false)
  if (!valid) return
  dishSubmitting.value = true
  try {
    const payload = {
      ...dishForm,
      ingredients: listTextToJson(dishForm.ingredients),
      allergens: listTextToJson(dishForm.allergens),
    }
    delete payload.id
    if (isEdit.value) {
      await request.put(`/api/admin/dish/update/${dishForm.id}`, payload)
      ElMessage.success('更新成功')
    } else {
      await request.post('/api/admin/dish/create', payload)
      ElMessage.success('新增成功')
    }
    dishDialogVisible.value = false
    loadDishes()
  } catch (e) {}
  finally { dishSubmitting.value = false }
}

// 上下架切换
async function handleToggleStatus(row, val) {
  try {
    await request.put(`/api/admin/dish/update/${row.id}`, { ...row, status: val ? 1 : 0 })
    row.status = val ? 1 : 0
    ElMessage.success(val ? '已上架' : '已下架')
  } catch (e) {}
}

// 删除菜品
async function handleDelete(id) {
  try {
    await ElMessageBox.confirm('确定要删除该菜品吗？', '提示', { type: 'warning' })
    await request.delete(`/api/admin/dish/delete/${id}`)
    ElMessage.success('删除成功')
    loadDishes()
  } catch (e) {}
}

// ---- 分类管理 ----
function openCategoryDialog() {
  newCatName.value = ''
  newCatSort.value = 0
  catDialogVisible.value = true
}

async function addCategory() {
  if (!newCatName.value.trim()) { ElMessage.warning('请输入分类名称'); return }
  try {
    await request.post('/api/admin/dish/category/add', {
      name: newCatName.value.trim(),
      sort: newCatSort.value,
    })
    ElMessage.success('添加成功')
    newCatName.value = ''
    newCatSort.value = 0
    loadCategories()
  } catch (e) {}
}

async function handleDeleteCategory(id) {
  try {
    await ElMessageBox.confirm('确定要删除该分类吗？', '提示', { type: 'warning' })
    await request.delete(`/api/admin/dish/category/delete/${id}`)
    ElMessage.success('删除成功')
    loadCategories()
  } catch (e) {}
}

onMounted(() => {
  loadCategories()
  loadDishes()
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-title {
  font-size: 20px;
  color: #303133;
}
.category-card {
  margin-bottom: 16px;
}
.category-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.table-card {
  margin-bottom: 16px;
}
.no-image {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
