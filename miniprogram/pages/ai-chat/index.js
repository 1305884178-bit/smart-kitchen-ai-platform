// pages/ai-chat/index.js
const { createSSE } = require('../../utils/sse');

// 送入服务的最近消息条数（含当前条），与服务端 CHAT_HISTORY_MAX_MESSAGES 对齐
const HISTORY_LIMIT = 8;

Page({
  data: {
    messages: [],
    inputValue: '',
    sending: false,
    aiTyping: false,
    scrollToView: ''
  },

  onShow() {
    this._ensureConversationId();
    // 初始化欢迎消息
    if (this.data.messages.length === 0) {
      this.setData({
        messages: [{
          role: 'assistant',
          content: '你好！我是智慧后厨的AI客服，可以帮你推荐菜品、查询库存和配料信息，请问有什么可以帮你的？'
        }]
      });
    }
  },

  /**
   * 本地生成/复用会话 ID（不上 Redis session，仅用于服务端日志与限流兜底）
   */
  _ensureConversationId() {
    if (this.conversationId) return;
    let cid = wx.getStorageSync('ai_conversation_id');
    if (!cid) {
      cid = 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
      wx.setStorageSync('ai_conversation_id', cid);
    }
    this.conversationId = cid;
  },

  /**
   * 输入框内容变化
   */
  onInput(e) {
    this.setData({ inputValue: e.detail.value });
  },

  /**
   * 发送消息
   */
  onSend() {
    const question = this.data.inputValue.trim();
    if (!question || this.data.sending) return;

    // 添加用户消息
    const messages = [...this.data.messages, {
      role: 'user',
      content: question
    }];
    this.setData({
      messages,
      inputValue: '',
      sending: true,
      aiTyping: true
    });

    // 滚动到底部
    this._scrollToBottom();

    // 添加一个空的 AI 消息占位
    const aiMsgIndex = messages.length;
    messages.push({ role: 'assistant', content: '' });
    this.setData({ messages });

    let fullContent = '';

    // 多轮上下文：本地最近 N 条真实消息（过滤掉正在流式输出的空占位消息）
    const historyMessages = messages
      .filter(m => m.content && m.content.trim())
      .slice(-HISTORY_LIMIT)
      .map(m => ({ role: m.role, content: m.content }));

    // 创建 SSE 连接接收流式回复
    this._sse = createSSE({
      baseUrl: getApp().globalData.aiBase || 'http://localhost:8000',
      url: '/ai/chat',
      data: {
        message: question,
        conversation_id: this.conversationId,
        messages: historyMessages
      },
      onMessage: (chunk) => {
        let content = chunk;
        try {
          const parsed = JSON.parse(chunk);
          content = parsed.content || chunk;
        } catch (e) {}
        fullContent += content;
        messages[aiMsgIndex] = {
          role: 'assistant',
          content: fullContent
        };
        this.setData({
          messages,
          aiTyping: false
        });
        this._scrollToBottom();
      },
      onComplete: () => {
        this.setData({
          sending: false,
          aiTyping: false
        });
        this._sse = null;
      },
      onError: (err) => {
        console.error('SSE error:', err);
        if (fullContent === '') {
          messages[aiMsgIndex] = {
            role: 'assistant',
            content: '抱歉，AI客服暂时不可用，请稍后再试。'
          };
        }
        this.setData({
          messages,
          sending: false,
          aiTyping: false
        });
        this._sse = null;
      }
    });
  },

  /**
   * 滚动到底部
   */
  _scrollToBottom() {
    const len = this.data.messages.length;
    if (len > 0) {
      this.setData({ scrollToView: `msg-${len - 1}` });
    }
  }
});
