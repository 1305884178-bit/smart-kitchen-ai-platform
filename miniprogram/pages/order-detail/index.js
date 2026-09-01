// pages/order-detail/index.js
const request = require('../../utils/request');

// 订单状态映射（与后端 OrderStatusEnum 一致）
const STATUS_MAP = {
  0: '已下单',
  10: '已上菜',
  20: '已结账',
  90: '已取消'
};

// 状态对应可操作按钮
const ACTION_MAP = {
  0: [{ label: '加菜', type: 'ADD_DISH' }, { label: '结账', type: 'PAY' }],
  1: [{ label: '结账', type: 'PAY' }],
  2: [{ label: '评价', type: 'REVIEW' }]
};

Page({
  data: {
    order: null,
    loading: true,
    statusText: '',
    actions: [],
    orderId: null,
    pollingTimer: null,
    createTimeFormatted: '',
    countdownText: ''
  },

  onLoad(options) {
    const orderId = options.id;
    if (orderId) {
      this.setData({ orderId });
      this._loadOrderDetail();
    }
  },

  onShow() {
    if (this.data.orderId) {
      this._loadOrderDetail();
    }
  },

  onHide() {
    this._stopPolling();
    this._stopCountdown();
  },

  onUnload() {
    this._stopPolling();
    this._stopCountdown();
  },

  /**
   * 加载订单详情
   */
  _loadOrderDetail() {
    request.get(`/api/order/my-detail/${this.data.orderId}`)
      .then(order => {
        const statusText = STATUS_MAP[order.status] || '未知';
        const availableActions = order.availableActions || [];
        const actions = availableActions.map(a => ({
          label: this._getActionLabel(a),
          type: a
        }));
        const createTimeFormatted = this._formatTime(order.createTime);
        this.setData({ order, statusText, actions, loading: false, createTimeFormatted });
        this._startPayCountdown(order);
      })
      .catch(() => {
        this.setData({ loading: false });
      });
  },

  /**
   * 先付后做：未支付订单展示支付倒计时（createTime + 15 分钟），到期自动刷新（可能已被超时取消）
   */
  _startPayCountdown(order) {
    this._stopCountdown();
    // 仅父单未支付且状态为已下单时倒计时（加菜待补付的场景以父单口径不展示倒计时）
    if (!order || order.status !== 0 || order.payTime) {
      this.setData({ countdownText: '' });
      return;
    }
    const createTs = this._parseTime(order.createTime);
    if (!createTs) return;
    const deadline = createTs + 15 * 60 * 1000;
    const tick = () => {
      const remain = deadline - Date.now();
      if (remain <= 0) {
        this._stopCountdown();
        this.setData({ countdownText: '' });
        // 到期刷新详情：超时单可能已被 MQ/扫表取消
        this._loadOrderDetail();
        return;
      }
      const mm = String(Math.floor(remain / 60000)).padStart(2, '0');
      const ss = String(Math.floor((remain % 60000) / 1000)).padStart(2, '0');
      this.setData({ countdownText: `请在 ${mm}:${ss} 内完成支付，超时订单将自动取消` });
    };
    tick();
    this.data.countdownTimer = setInterval(tick, 1000);
  },

  _stopCountdown() {
    if (this.data.countdownTimer) {
      clearInterval(this.data.countdownTimer);
      this.data.countdownTimer = null;
    }
  },

  /**
   * 解析后端时间（兼容 iOS 日期解析）
   */
  _parseTime(timeStr) {
    if (!timeStr) return 0;
    const ts = new Date(String(timeStr).replace('T', ' ').replace(/-/g, '/')).getTime();
    return isNaN(ts) ? 0 : ts;
  },

  /**
   * 获取操作按钮文案
   */
  _getActionLabel(action) {
    const labels = {
      ADD_DISH: '加菜',
      PAY: '支付',
      REVIEW: '评价'
    };
    return labels[action] || action;
  },

  /**
   * 操作按钮点击
   */
  onAction(e) {
    const action = e.currentTarget.dataset.action;
    const { orderId } = this.data;

    switch (action) {
      case 'ADD_DISH':
        getApp().globalData.addToOrderId = orderId;
        getApp().globalData.addToSeatNumber = this.data.order?.seatNumber || '';
        wx.switchTab({ url: '/pages/menu/index' });
        break;
      case 'PAY':
        this._onPay();
        break;
      case 'REVIEW':
        wx.navigateTo({ url: `/pages/review/index?orderId=${orderId}` });
        break;
    }
  },

  /**
   * 支付
   */
  _onPay() {
    wx.showModal({
      title: '确认支付',
      content: `确认支付 ¥${this.data.order.totalAmount} 吗？`,
      success: (res) => {
        if (res.confirm) {
          request.post(`/api/order/${this.data.orderId}/pay`)
            .then(() => {
              wx.showToast({ title: '支付成功', icon: 'success' });
              this._loadOrderDetail();
            });
        }
      }
    });
  },

  /**
   * 停止轮询
   */
  _stopPolling() {
    if (this.data.pollingTimer) {
      clearInterval(this.data.pollingTimer);
      this.data.pollingTimer = null;
    }
  },

  /**
   * 格式化时间
   */
  _formatTime(timeStr) {
    if (!timeStr) return '';
    return timeStr.replace('T', ' ').substring(0, 16);
  }
});
