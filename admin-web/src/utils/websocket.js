/**
 * WebSocket 客户端封装
 * 功能：心跳保活 + 断线自动重连 + 重连后回调 HTTP 快照补齐
 * 连接时 URL 携带 ?token=xxx 参数用于后端握手校验
 */

const HEARTBEAT_INTERVAL = 30000 // 心跳间隔 30s
const RECONNECT_DELAY = 3000 // 重连延迟 3s
const MAX_RECONNECT_ATTEMPTS = 10 // 最大重连次数

/**
 * 创建 WebSocket 连接
 * @param {string} wsPath - WebSocket 路径，如 '/ws/kitchen-board'
 * @param {object} options - 配置项
 * @param {function} options.onMessage - 收到消息回调 (data) => void
 * @param {function} options.onOpen - 连接成功回调 (event) => void
 * @param {function} options.onClose - 连接关闭回调 (event) => void
 * @param {function} options.onError - 连接错误回调 (event) => void
 * @param {function} options.onReconnectFailed - 重连失败回调 () => void
 * @param {function} options.getSnapshot - HTTP 快照补齐函数，重连后调用，返回 Promise
 * @returns {{ close: function, send: function }}
 */
export function createWebSocket(wsPath, options = {}) {
  const {
    onMessage,
    onOpen,
    onClose,
    onError,
    onReconnectFailed,
    getSnapshot,
  } = options

  let ws = null
  let heartbeatTimer = null
  let reconnectTimer = null
  let reconnectAttempts = 0
  let isManualClose = false

  function connect() {
    const token = localStorage.getItem('token')
    if (!token) {
      console.warn('[WebSocket] 无 token，跳过连接')
      return
    }

    const baseUrl = import.meta.env.VITE_WS_URL || 'ws://localhost:8080'
    const url = `${baseUrl}${wsPath}?token=${token}`

    ws = new WebSocket(url)

    ws.onopen = (event) => {
      console.log(`[WebSocket] 连接成功: ${wsPath}`)
      reconnectAttempts = 0
      startHeartbeat()

      // 重连成功后通过 HTTP 快照补齐数据
      if (getSnapshot) {
        getSnapshot()
          .then(() => console.log('[WebSocket] HTTP 快照补齐完成'))
          .catch((err) => console.error('[WebSocket] HTTP 快照补齐失败:', err))
      }

      if (onOpen) onOpen(event)
    }

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        if (onMessage) onMessage(data)
      } catch (e) {
        console.warn('[WebSocket] 消息解析失败:', event.data)
      }
    }

    ws.onclose = (event) => {
      console.log(`[WebSocket] 连接关闭: ${wsPath}, code=${event.code}`)
      stopHeartbeat()

      if (onClose) onClose(event)

      // 非手动关闭时尝试重连
      if (!isManualClose) {
        tryReconnect()
      }
    }

    ws.onerror = (event) => {
      console.error(`[WebSocket] 连接错误: ${wsPath}`)
      if (onError) onError(event)
    }
  }

  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'PING' }))
      }
    }, HEARTBEAT_INTERVAL)
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function tryReconnect() {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      console.error(`[WebSocket] 重连次数已达上限(${MAX_RECONNECT_ATTEMPTS})`)
      if (onReconnectFailed) onReconnectFailed()
      return
    }

    reconnectAttempts++
    console.log(
      `[WebSocket] 第 ${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS} 次重连尝试...`
    )

    reconnectTimer = setTimeout(() => {
      connect()
    }, RECONNECT_DELAY)
  }

  function close() {
    isManualClose = true
    stopHeartbeat()
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      ws.close()
      ws = null
    }
  }

  function send(data) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(typeof data === 'string' ? data : JSON.stringify(data))
    } else {
      console.warn('[WebSocket] 连接未就绪，无法发送消息')
    }
  }

  // 启动连接
  connect()

  return { close, send }
}
