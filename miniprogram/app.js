/**
 * 智慧后厨小程序 - 应用入口
 * 启动时校验登录态，未登录则引导微信授权
 */
App({
  onLaunch() {
    const token = wx.getStorageSync('token');
    if (token) {
      this._validateToken(token);
    }
  },

  /**
   * 校验 token 有效性，失效则清除登录态
   */
  _validateToken(token) {
    wx.request({
      url: `${this.globalData.apiBase}/api/auth/check-token`,
      method: 'GET',
      header: { 'Authorization': `Bearer ${token}` },
      success: (res) => {
        if (res.statusCode === 401 || res.data?.code !== 200) {
          this._tryRefresh().then((ok) => {
            if (!ok) this._clearAuth();
          });
        } else {
          const { userId, role } = res.data.data || {};
          this.globalData.isLoggedIn = true;
          this.globalData.userId = userId;
          this.globalData.role = role;
          this.globalData.userInfo = { userId, role };
        }
      },
      fail: () => {
        this.globalData.isLoggedIn = true;
      }
    });
  },

  _saveAuth(data) {
    if (data?.token) {
      wx.setStorageSync('token', data.token);
    }
    if (data?.refreshToken) {
      wx.setStorageSync('refreshToken', data.refreshToken);
    }
    this.globalData.isLoggedIn = true;
    if (data?.userId) {
      this.globalData.userId = data.userId;
      this.globalData.role = data.role;
      this.globalData.userInfo = { userId: data.userId, role: data.role };
    }
  },

  /**
   * Access Token 过期时用 Refresh Token 换发
   * @returns {Promise<boolean>}
   */
  _tryRefresh() {
    const refreshToken = wx.getStorageSync('refreshToken');
    if (!refreshToken) {
      return Promise.resolve(false);
    }
    return new Promise((resolve) => {
      wx.request({
        url: `${this.globalData.apiBase}/api/auth/refresh`,
        method: 'POST',
        data: { refreshToken },
        success: (res) => {
          if (res.statusCode === 200 && res.data?.code === 200 && res.data.data?.token) {
            this._saveAuth(res.data.data);
            resolve(true);
          } else {
            resolve(false);
          }
        },
        fail: () => resolve(false)
      });
    });
  },

  /**
   * 清除登录态
   */
  _clearAuth() {
    wx.removeStorageSync('token');
    wx.removeStorageSync('refreshToken');
    wx.removeStorageSync('userInfo');
    this.globalData.isLoggedIn = false;
  },

  /**
   * 执行微信登录流程
   * @returns {Promise<string|null>} 成功返回 token，新用户返回 null
   */
  doWxLogin() {
    return new Promise((resolve, reject) => {
      wx.login({
        success: (loginRes) => {
          if (!loginRes.code) {
            reject(new Error('wx.login 失败'));
            return;
          }
          wx.request({
            url: `${this.globalData.apiBase}/api/auth/wx-login`,
            method: 'POST',
            data: { code: loginRes.code },
            success: (res) => {
              if (res.statusCode === 200 && res.data?.code === 200) {
                const data = res.data.data || {};
                if (data.token) {
                  this._saveAuth(data);
                  resolve(data.token);
                } else {
                  // 新用户需要注册
                  resolve(null);
                }
              } else {
                reject(new Error(res.data?.message || '登录失败'));
              }
            },
            fail: (err) => reject(err)
          });
        },
        fail: (err) => reject(err)
      });
    });
  },

  /**
   * 全局登录校验：未登录则引导登录流程
   * 每个需要登录的页面在 onShow 中调用此方法
   * @returns {Promise<boolean>} true=已登录，false=未登录（已跳转）
   */
  checkLogin() {
    return new Promise((resolve) => {
      const token = wx.getStorageSync('token');
      if (token && this.globalData.isLoggedIn) {
        resolve(true);
        return;
      }
      // 执行登录流程
      this.doWxLogin()
        .then((result) => {
          if (result) {
            // 老用户登录成功
            resolve(true);
          } else {
            // 新用户，跳转注册页
            wx.navigateTo({ url: '/pages/register/index' });
            resolve(false);
          }
        })
        .catch(() => {
          wx.showToast({ title: '登录失败，请重试', icon: 'none' });
          resolve(false);
        });
    });
  },

  globalData: {
    apiBase: 'http://localhost:8080',
    aiBase: 'http://localhost:8000',
    wsBase: 'ws://localhost:8080',
    isLoggedIn: false,
    userId: null,
    role: null,
    userInfo: null
  }
});
