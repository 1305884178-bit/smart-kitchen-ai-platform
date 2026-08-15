/**
 * 网络请求封装
 * - 自动注入 Authorization header
 * - 响应拦截：401 → 清除 token 并触发重新登录
 * - 统一错误提示
 */
const app = getApp();

// 请求基础配置
const BASE_URL = app?.globalData?.apiBase || 'http://localhost:8080';
const TIMEOUT = 15000;

/**
 * 通用请求方法（Promise 封装 wx.request）
 * @param {string} url - 请求路径（相对于 BASE_URL）
 * @param {object} options - 请求配置 { method, data, header, showLoading, showError }
 * @returns {Promise<any>} 返回 data.data（后端统一响应体）
 */
function request(url, options = {}) {
  const {
    method = 'GET',
    data = {},
    header = {},
    showLoading = false,
    showError = true
  } = options;

  if (showLoading) {
    wx.showLoading({ title: '加载中...', mask: true });
  }

  // 注入 Authorization token
  const token = wx.getStorageSync('token');
  const headers = {
    'Content-Type': 'application/json',
    ...header
  };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  return new Promise((resolve, reject) => {
    wx.request({
      url: `${BASE_URL}${url}`,
      method,
      data,
      header: headers,
      timeout: TIMEOUT,
      success: (res) => {
        if (showLoading) wx.hideLoading();

        // 401 未授权：清除 token，触发重新登录
        if (res.statusCode === 401) {
          wx.removeStorageSync('token');
          app.globalData.isLoggedIn = false;
          if (showError) {
            wx.showToast({ title: '登录已过期，请重新登录', icon: 'none' });
          }
          // 自动触发登录流程：老用户静默换 token，新用户跳转注册页
          app.checkLogin();
          reject({ code: 401, message: 'Unauthorized' });
          return;
        }

        // 业务状态码判断
        const body = res.data;
        if (res.statusCode === 200 && body?.code === 200) {
          resolve(body.data);
        } else {
          if (showError) {
            wx.showToast({
              title: body?.message || '请求失败',
              icon: 'none'
            });
          }
          reject(body || { code: res.statusCode, message: '请求失败' });
        }
      },
      fail: (err) => {
        if (showLoading) wx.hideLoading();
        if (showError) {
          wx.showToast({
            title: '网络异常，请稍后重试',
            icon: 'none'
          });
        }
        reject(err);
      }
    });
  });
}

/**
 * GET 请求
 */
function get(url, params = {}, options = {}) {
  // 将 params 拼接为 query string
  if (params && Object.keys(params).length > 0) {
    const query = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
      .join('&');
    if (query) url = `${url}?${query}`;
  }
  return request(url, { ...options, method: 'GET' });
}

/**
 * POST 请求
 */
function post(url, data = {}, options = {}) {
  return request(url, { ...options, method: 'POST', data });
}

/**
 * PUT 请求
 */
function put(url, data = {}, options = {}) {
  return request(url, { ...options, method: 'PUT', data });
}

/**
 * DELETE 请求
 */
function del(url, options = {}) {
  return request(url, { ...options, method: 'DELETE' });
}

module.exports = { get, post, put, del, request };
