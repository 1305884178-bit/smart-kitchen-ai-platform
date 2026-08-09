// pages/my-orders/index.js
const request = require('../../utils/request');

Page({
  data: {
    orders: [],
    page: 1,
    size: 10,
    hasMore: true,
    loading: false,
    loadingMore: false
  },

  onShow() {
    this.setData({ page: 1, orders: [], hasMore: true });
    this._loadOrders();
  },

  /**
   * 加载订单列表（分页）
   */
  _loadOrders() {
    const { page, size, loading } = this.data;
    if (loading) return;

    const isFirstPage = page === 1;
    this.setData({
      loading: true,
      loadingMore: !isFirstPage
    });

    request.get('/api/order/my-list', { page, size })
      .then(result => {
        const records = result.records || [];
        const newOrders = isFirstPage ? records : [...this.data.orders, ...records];
        this.setData({
          orders: newOrders,
          hasMore: records.length >= size,
          page: page + 1,
          loading: false,
          loadingMore: false
        });
      })
      .catch(() => {
        this.setData({ loading: false, loadingMore: false });
      });
  },

  /**
   * 上拉加载更多
   */
  onReachBottom() {
    if (this.data.hasMore && !this.data.loadingMore) {
      this._loadOrders();
    }
  },

  /**
   * 点击订单跳转详情
   */
  onOrderTap(e) {
    const orderId = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/order-detail/index?id=${orderId}` });
  },

  /**
   * 获取状态文本
   */
  _statusText(status) {
    const map = { 0: '已下单', 1: '已出餐', 2: '已结账', 3: '已取消' };
    return map[status] || '未知';
  },

  /**
   * 菜品名称拼接
   */
  _dishNames(details) {
    if (!details || details.length === 0) return '';
    return details.map(d => d.dishName).join('、');
  },

  /**
   * 格式化时间
   */
  _formatTime(timeStr) {
    if (!timeStr) return '';
    return timeStr.replace('T', ' ').substring(0, 16);
  }
});
