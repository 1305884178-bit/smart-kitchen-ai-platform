// pages/dish-detail/index.js
const request = require('../../utils/request');
const cart = require('../../utils/cart');

Page({
  data: {
    dish: null,
    loading: true
  },

  onLoad(options) {
    const dishId = options.id;
    if (dishId) {
      this._loadDishDetail(dishId);
    }
  },

  /**
   * 加载菜品详情（顾客端接口）
   */
  _loadDishDetail(dishId) {
    request.get(`/api/dish/detail/${dishId}`)
      .then(dish => {
        this.setData({ dish, loading: false });
      })
      .catch(() => {
        this.setData({ loading: false });
        wx.showToast({ title: '加载失败', icon: 'none' });
      });
  },

  /**
   * 加入购物车
   */
  onAddToCart() {
    const { dish } = this.data;
    if (!dish || dish.dailyStock <= 0) {
      wx.showToast({ title: '该菜品已售罄', icon: 'none' });
      return;
    }
    cart.addToCart({
      dishId: dish.id,
      dishName: dish.name,
      price: dish.price,
      image: dish.image
    });
    wx.showToast({ title: '已加入购物车', icon: 'success' });
  },

  /**
   * 解析配料/过敏原（ingredients 字段为JSON字符串）
   */
  _parseIngredients(ingredientsStr) {
    if (!ingredientsStr) return [];
    try {
      return JSON.parse(ingredientsStr);
    } catch (e) {
      return [];
    }
  }
});
