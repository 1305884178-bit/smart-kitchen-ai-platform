// pages/ai-chat/index.js
const { createSSE } = require('../../utils/sse');

Page({
  data: {
    messages: [],
    inputValue: '',
    sending: false,
    aiTyping: false,
    scrollToView: ''
  },

  onShow() {
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

    // 创建 SSE 连接接收流式回复
    this._sse = createSSE({
      baseUrl: getApp().globalData.aiBase || 'http://localhost:8000',
      url: '/ai/chat',
      data: { message: question },
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
