// pages/menu/index.js
const request = require('../../utils/request');
const cart = require('../../utils/cart');

Page({
  data: {
    categories: [],
    activeCategoryId: 0,
    dishes: [],
    cartCount: 0,
    cartTotal: 0,
    showCartPopup: false,
    cartItems: [],
    allDishStatusMap: {},
    addToOrderId: null,
    addToSeatNumber: '',
    addingDish: false
  },

  onLoad() {
  },

  onShow() {
    // 从 globalData 读取加菜订单ID和座位号（TabBar 页面无法通过 URL 传参）
    const addToOrderId = getApp().globalData.addToOrderId;
    const addToSeatNumber = getApp().globalData.addToSeatNumber;
    if (addToOrderId) {
      this.setData({
        addToOrderId,
        addToSeatNumber: addToSeatNumber || '',
        addingDish: true
      });
      getApp().globalData.addToOrderId = null;
      getApp().globalData.addToSeatNumber = null;
    }
    this._loadCategories();
    this._loadDishes();
    this._loadAllDishesForCart();
    this._updateCartBadge();
  },

  /**
   * 加载全部在售菜品（不分分类），用于购物车校验已下架菜品
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
   * 加载分类列表
   */
  _loadCategories() {
    request.get('/api/admin/dish/category/list', {}, { showError: false })
      .then(categories => {
        const all = [{ id: 0, name: '全部' }, ...categories];
        this.setData({ categories: all });
      })
      .catch(() => {
        this.setData({
          categories: [{ id: 0, name: '全部' }]
        });
      });
  },

  /**
   * 加载菜品列表（按分类筛选）
   */
  _loadDishes() {
    const { activeCategoryId } = this.data;
    const params = activeCategoryId > 0 ? { categoryId: activeCategoryId } : {};
    request.get('/api/dish/list', params, { showLoading: true })
      .then(dishes => {
        const filtered = dishes.filter(d => d.status === 1);
        this.setData({ dishes: filtered });
      })
      .catch(() => {
        this.setData({ dishes: [] });
      });
  },

  /**
   * 切换分类
   */
  onCategoryTap(e) {
    const id = e.currentTarget.dataset.id;
    this.setData({ activeCategoryId: id }, () => {
      this._loadDishes();
    });
  },

  /**
   * 根据缓存的菜品状态，重新计算购物车 active 状态和总金额
   */
  _applyCartStatus() {
    const dishStatusMap = this.data.allDishStatusMap || {};
    const items = cart.getCartItems().map(item => ({
      ...item,
      active: dishStatusMap[item.dishId] !== undefined ? dishStatusMap[item.dishId] === 1 : false
    }));

    const activeTotal = items
      .filter(i => i.active)
      .reduce((sum, i) => sum + i.price * i.quantity, 0);

    this.setData({
      cartCount: cart.getTotalCount(),
      cartTotal: activeTotal,
      cartItems: items
    });
  },

  /**
   * 加入购物车
   */
  onAddToCart(e) {
    const dish = e.currentTarget.dataset.dish;
    cart.addToCart({
      dishId: dish.id,
      dishName: dish.name,
      price: dish.price,
      image: dish.image
    });
    wx.showToast({ title: `已加入购物车`, icon: 'success', duration: 1000 });
    this._updateCartBadge();
  },

  /**
   * 点击菜品卡片跳转详情
   */
  onDishTap(e) {
    const dishId = e.currentTarget.dataset.dishId;
    wx.navigateTo({ url: `/pages/dish-detail/index?id=${dishId}` });
  },

  /**
   * 更新购物车角标和弹窗数据
   */
  _updateCartBadge() {
    this._applyCartStatus();
  },

  /**
   * 打开购物车弹窗
   */
  onGoToOrder() {
    if (this.data.cartCount === 0) {
      wx.showToast({ title: '购物车为空', icon: 'none' });
      return;
    }
    this.setData({ showCartPopup: true });
    this._updateCartBadge();
  },

  /**
   * 关闭购物车弹窗
   */
  onCloseCartPopup() {
    this.setData({ showCartPopup: false });
  },

  /**
   * 购物车内增加菜品数量
   */
  onCartIncrease(e) {
    const dishId = e.currentTarget.dataset.dishId;
    const item = this.data.cartItems.find(i => i.dishId === dishId);
    if (item) {
      cart.updateQuantity(dishId, item.quantity + 1);
      this._updateCartBadge();
    }
  },

  /**
   * 购物车内减少菜品数量，减到0则移除
   */
  onCartDecrease(e) {
    const dishId = e.currentTarget.dataset.dishId;
    const item = this.data.cartItems.find(i => i.dishId === dishId);
    if (item) {
      cart.updateQuantity(dishId, item.quantity - 1);
      this._updateCartBadge();
      // 如果购物车清空则关闭弹窗
      if (cart.getTotalCount() === 0) {
        this.setData({ showCartPopup: false });
      }
    }
  },

  /**
   * 从购物车弹窗跳转到下单页 / 提交加菜
   */
  onCartGoToOrder() {
    if (this.data.addingDish) {
      this._submitAddDish();
    } else {
      this.setData({ showCartPopup: false });
      wx.navigateTo({ url: '/pages/order/index' });
    }
  },

  /**
   * 底部栏"去下单"直接跳转到下单页 / 提交加菜
   */
  onGoToOrderDirect() {
    if (this.data.cartCount === 0) {
      wx.showToast({ title: '购物车为空', icon: 'none' });
      return;
    }
    if (this.data.addingDish) {
      this._submitAddDish();
    } else {
      wx.navigateTo({ url: '/pages/order/index' });
    }
  },

  /**
   * 提交加菜请求
   */
  _submitAddDish() {
    const { addToOrderId, cartItems, addToSeatNumber } = this.data;
    // 弹窗确认座位号，不可更改
    wx.showModal({
      title: '确认加菜',
      content: `座位号：${addToSeatNumber}（不可更改）\n确认提交所选的${cartItems.length}种菜品？`,
      confirmText: '确认',
      success: (res) => {
        if (!res.confirm) return;
        this._doSubmitAddDish(addToOrderId, cartItems);
      }
    });
  },

  /**
   * 执行加菜API请求
   */
  _doSubmitAddDish(addToOrderId, cartItems) {
    const details = cartItems.map(item => ({
      dishId: item.dishId,
      quantity: item.quantity
    }));
    request.post(`/api/order/${addToOrderId}/add-dish`, { details }, { showLoading: true })
      .then(() => {
        cart.clearCart();
        this.setData({
          addToOrderId: null,
          addToSeatNumber: '',
          addingDish: false,
          showCartPopup: false
        });
        this._updateCartBadge();
        wx.showToast({ title: '加菜成功', icon: 'success' });
        setTimeout(() => {
          wx.switchTab({ url: '/pages/my-orders/index' });
        }, 1000);
      });
  }
});
