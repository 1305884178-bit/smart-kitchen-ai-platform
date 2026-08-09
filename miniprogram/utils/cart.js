/**
 * 购物车状态管理
 * 使用 wx.setStorageSync 持久化购物车数据
 */

const CART_KEY = 'cart_items';

/**
 * 获取购物车中所有菜品
 * @returns {Array<{dishId: number, dishName: string, price: number, quantity: number, image: string}>}
 */
function getCartItems() {
  try {
    return wx.getStorageSync(CART_KEY) || [];
  } catch (e) {
    return [];
  }
}

/**
 * 保存购物车数据
 */
function saveCartItems(items) {
  wx.setStorageSync(CART_KEY, items);
}

/**
 * 添加菜品到购物车，已存在则增加数量
 * @param {{dishId: number, dishName: string, price: number, image: string}} dish
 * @param {number} quantity 数量，默认1
 */
function addToCart(dish, quantity = 1) {
  const items = getCartItems();
  const existIndex = items.findIndex(item => item.dishId === dish.dishId);
  if (existIndex >= 0) {
    items[existIndex].quantity += quantity;
  } else {
    items.push({
      dishId: dish.dishId,
      dishName: dish.dishName,
      price: dish.price,
      quantity: quantity,
      image: dish.image || ''
    });
  }
  saveCartItems(items);
}

/**
 * 更新菜品数量
 * @param {number} dishId
 * @param {number} quantity 新数量，<=0 时移除
 */
function updateQuantity(dishId, quantity) {
  const items = getCartItems();
  if (quantity <= 0) {
    removeFromCart(dishId);
    return;
  }
  const item = items.find(item => item.dishId === dishId);
  if (item) {
    item.quantity = quantity;
    saveCartItems(items);
  }
}

/**
 * 移除菜品
 * @param {number} dishId
 */
function removeFromCart(dishId) {
  const items = getCartItems().filter(item => item.dishId !== dishId);
  saveCartItems(items);
}

/**
 * 清空购物车
 */
function clearCart() {
  saveCartItems([]);
}

/**
 * 获取购物车总数量
 * @returns {number}
 */
function getTotalCount() {
  return getCartItems().reduce((sum, item) => sum + item.quantity, 0);
}

/**
 * 获取购物车总金额
 * @returns {number}
 */
function getTotalAmount() {
  return getCartItems().reduce((sum, item) => sum + item.price * item.quantity, 0);
}

module.exports = {
  getCartItems,
  addToCart,
  updateQuantity,
  removeFromCart,
  clearCart,
  getTotalCount,
  getTotalAmount
};
