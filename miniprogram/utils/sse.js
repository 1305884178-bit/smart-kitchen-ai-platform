/**
 * SSE 客户端实现
 * 小程序不支持原生 EventSource，使用 wx.request + enableChunked 实现流式读取。
 * 低版本基础库不兼容时，降级为普通 POST 等待完整响应。
 *
 * 使用方式：
 *   const sse = createSSE({
 *     url: '/api/ai/chat',
 *     data: { question: '今天推荐什么菜？' },
 *     onMessage: (chunk) => console.log(chunk),
 *     onComplete: () => console.log('done'),
 *     onError: (err) => console.error(err)
 *   });
 *   // 如需取消：sse.abort();
 */
const app = getApp();
const DEFAULT_BASE_URL = app?.globalData?.apiBase || 'http://localhost:8080';

/**
 * 检查是否支持 enableChunked（基础库 >= 2.20.1）
 */
function _supportChunked() {
  const sdkVersion = wx.getSystemInfoSync().SDKVersion;
  return _compareVersion(sdkVersion, '2.20.1') >= 0;
}

function _compareVersion(v1, v2) {
  const parts1 = v1.split('.').map(Number);
  const parts2 = v2.split('.').map(Number);
  for (let i = 0; i < Math.max(parts1.length, parts2.length); i++) {
    const a = parts1[i] || 0;
    const b = parts2[i] || 0;
    if (a > b) return 1;
    if (a < b) return -1;
  }
  return 0;
}

/**
 * 创建 SSE 连接
 * @param {object} config
 * @param {string} config.url - 请求路径
 * @param {object} config.data - 请求体
 * @param {function(string)} config.onMessage - 收到流式数据片段的回调
 * @param {function} config.onComplete - 流结束回调
 * @param {function(Error)} config.onError - 错误回调
 * @param {object} config.header - 额外请求头
 * @returns {{ abort: function }} 返回可调用 abort 取消请求的对象
 */
function createSSE(config) {
  let aborted = false;
  let requestTask = null;
  let buffer = '';
  const baseUrl = config.baseUrl || DEFAULT_BASE_URL;

  if (!config.url || !config.onMessage) {
    throw new Error('SSE: url 和 onMessage 为必填参数');
  }

  const token = wx.getStorageSync('token');
  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream',
    ...(config.header || {})
  };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  if (_supportChunked()) {
    // 使用 enableChunked 流式接收
    requestTask = wx.request({
      url: `${baseUrl}${config.url}`,
      method: 'POST',
      data: config.data || {},
      header: headers,
      enableChunked: true,
      success: () => {
        // 完成回调在 onChunkReceived 中最后一块数据后触发
      },
      fail: (err) => {
        if (!aborted && config.onError) {
          config.onError(err);
        }
      }
    });

    // 监听流式数据块
    requestTask.onChunkReceived((res) => {
      if (aborted) return;
      try {
        const text = _arrayBufferToString(res.data);
        buffer += text;

        // 解析 SSE 格式：按 "data:" 开头的行分割
        const lines = buffer.split('\n');
        // 保留最后一个可能不完整的行
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (trimmed.startsWith('data:')) {
            const payload = trimmed.slice(5).trim();
            if (payload === '[DONE]') {
              if (config.onComplete) config.onComplete();
              return;
            }
            if (payload) {
              config.onMessage(payload);
            }
          }
        }
      } catch (e) {
        if (config.onError) config.onError(e);
      }
    });
  } else {
    // 降级：普通 POST 请求，等待完整响应
    wx.request({
      url: `${baseUrl}${config.url}`,
      method: 'POST',
      data: config.data || {},
      header: headers,
      success: (res) => {
        if (aborted) return;
        if (res.statusCode === 200 && res.data) {
          config.onMessage(res.data?.data || res.data);
          if (config.onComplete) config.onComplete();
        } else if (config.onError) {
          config.onError(new Error(res.data?.message || 'SSE 请求失败'));
        }
      },
      fail: (err) => {
        if (!aborted && config.onError) {
          config.onError(err);
        }
      }
    });
  }

  return {
    abort() {
      aborted = true;
      if (requestTask) {
        requestTask.abort();
      }
    }
  };
}

/**
 * ArrayBuffer 转字符串（安全转换）
 */
function _arrayBufferToString(buffer) {
  if (typeof buffer === 'string') return buffer;
  // 使用 Uint8Array 逐字节解码（兼容性最好）
  const arr = new Uint8Array(buffer);
  let result = '';
  for (let i = 0; i < arr.length; i++) {
    result += String.fromCharCode(arr[i]);
  }
  return decodeURIComponent(escape(result));
}

module.exports = { createSSE };
