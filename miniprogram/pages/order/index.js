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
    this._loadAllDishesForCart();
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
   * 加载购物车数据（原始数据）
   */
  _loadCartItems() {
    const items = cart.getCartItems();
    this.setData({ cartItems: items });
  },

  /**
   * 加载全部在售菜品状态，用于校验购物车中已下架菜品
   */
  _loadAllDishesForCart() {
    request.get('/api/dish/list', {}, { showError: false })
      .then(dishes => {
        const map = {};
        (dishes || []).forEach(d => { map[d.id] = d.status; });
        this.setData({ allDishStatusMap: map }, () => {
          this._applyCartStatus();
        });
      });
  },

  /**
   * 根据菜品状态重新计算购物车中每项的有效性和总金额
   */
  _applyCartStatus() {
    const dishStatusMap = this.data.allDishStatusMap || {};
    const items = this.data.cartItems.map(item => ({
      ...item,
      active: dishStatusMap[item.dishId] !== undefined ? dishStatusMap[item.dishId] === 1 : false
    }));

    const totalAmount = items
      .filter(i => i.active)
      .reduce((sum, i) => sum + i.price * i.quantity, 0);

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
      wx.showModal({
        title: '提示',
        content: '请先选择座位号',
        showCancel: false,
        confirmText: '知道了'
      });
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
