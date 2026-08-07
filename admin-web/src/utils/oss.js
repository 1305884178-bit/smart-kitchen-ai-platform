import OSS from 'ali-oss'
import request from './request'

let stsCache = null
let stsExpireTime = 0

async function getStsToken() {
  if (stsCache && Date.now() < stsExpireTime) {
    return stsCache
  }
  const res = await request.get('/api/admin/upload/sts-token')
  const data = res.data
  if (data.status !== 'success') {
    throw new Error(data.message || '获取上传凭证失败')
  }
  stsCache = data
  stsExpireTime = Date.now() + 3500 * 1000
  return data
}

export async function uploadToOss(file) {
  const sts = await getStsToken()
  const client = new OSS({
    region: sts.region,
    accessKeyId: sts.accessKeyId,
    accessKeySecret: sts.accessKeySecret,
    stsToken: sts.securityToken,
    bucket: sts.bucket,
  })
  const ext = file.name.substring(file.name.lastIndexOf('.'))
  const objectName = `dishes/${Date.now()}_${Math.random().toString(36).substring(2, 8)}${ext}`
  const result = await client.put(objectName, file)
  return result.url
}
