import request from './request'

/**
 * 上传图片到 OSS（通过后端代理，避免前端直传跨域问题）
 * @param {File} file 图片文件
 * @returns {Promise<string>} 图片公网访问 URL
 */
export async function uploadToOss(file) {
  const formData = new FormData()
  formData.append('file', file)

  const res = await request.post('/api/admin/upload/image', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return res.data
}
