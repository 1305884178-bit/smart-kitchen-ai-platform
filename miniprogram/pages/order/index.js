// pages/order/index.js
const request = require('../../utils/request');
const cart = require('../../utils/cart');

Page({
  data: {
    seats: [],
    selectedSeat: '',
    cartItems: [],
    totalAmount: 0,
    remark: '',
    submitting: false
  },

  onShow() {
    this._loadSeats();
    this._loadCartItems();
  },

  /**
   * 加载座位状态
   */
  _loadSeats() {
    request.get('/api/seat/available', {}, { showError: false })
      .then(seats => {
        this.setData({ seats });
      })
      .catch(() => {
        this.setData({ seats: [] });
      });
  },

  /**
   * 加载购物车数据
   */
  _loadCartItems() {
    const items = cart.getCartItems();
    const totalAmount = cart.getTotalAmount();
    this.setData({ cartItems: items, totalAmount });
  },

  /**
   * 选择座位
   */
  onSeatTap(e) {
    const seat = e.currentTarget.dataset.seat;
    const seatData = this.data.seats.find(s => s.seatNumber === seat);
    if (seatData && seatData.occupied) {
      wx.showToast({ title: '该座位已被占用', icon: 'none' });
      return;
    }
    this.setData({
      selectedSeat: this.data.selectedSeat === seat ? '' : seat
    });
  },

  /**
   * 输入备注
   */
  onRemarkInput(e) {
    this.setData({ remark: e.detail.value });
  },

  /**
   * 提交订单
   */
  onSubmit() {
    const { selectedSeat, cartItems, remark } = this.data;
    if (!selectedSeat) {
      wx.showToast({ title: '请选择座位', icon: 'none' });
      return;
    }
    if (cartItems.length === 0) {
      wx.showToast({ title: '购物车为空', icon: 'none' });
      return;
    }

    this.setData({ submitting: true });

    const details = cartItems.map(item => ({
      dishId: item.dishId,
      quantity: item.quantity
    }));

    request.post('/api/order/submit', {
      seatNumber: selectedSeat,
      remark: remark,
      details: details
    })
      .then(() => {
        cart.clearCart();
        wx.showToast({ title: '下单成功', icon: 'success' });
        setTimeout(() => {
          wx.reLaunch({ url: '/pages/my-orders/index' });
        }, 1000);
      })
      .catch(() => {
        this.setData({ submitting: false });
      });
  }
});
