// pages/menu/index.js
const request = require('../../utils/request');
const cart = require('../../utils/cart');

Page({
  data: {
    categories: [],
    activeCategoryId: 0,
    dishes: [],
    cartCount: 0,
    cartTotal: 0
  },

  onShow() {
    this._loadCategories();
    this._loadDishes();
    this._updateCartBadge();
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
   * 更新购物车角标
   */
  _updateCartBadge() {
    this.setData({
      cartCount: cart.getTotalCount(),
      cartTotal: cart.getTotalAmount()
    });
  },

  /**
   * 跳转到下单页
   */
  onGoToOrder() {
    if (this.data.cartCount === 0) {
      wx.showToast({ title: '购物车为空', icon: 'none' });
      return;
    }
    wx.navigateTo({ url: '/pages/order/index' });
  }
});
