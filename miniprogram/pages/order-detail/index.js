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
    createTimeFormatted: ''
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
  },

  onUnload() {
    this._stopPolling();
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
      })
      .catch(() => {
        this.setData({ loading: false });
      });
  },

  /**
   * 获取操作按钮文案
   */
  _getActionLabel(action) {
    const labels = {
      ADD_DISH: '加菜',
      PAY: '结账',
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
   * 结账支付
   */
  _onPay() {
    wx.showModal({
      title: '确认结账',
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
