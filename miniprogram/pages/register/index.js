// pages/register/index.js
const app = getApp();

Page({
  data: {
    nickname: '',
    avatar: '',
    phone: '',
    submitting: false
  },

  /**
   * 选择头像
   */
  onChooseAvatar(e) {
    const { avatarUrl } = e.detail;
    this.setData({ avatar: avatarUrl });
  },

  /**
   * 输入昵称
   */
  onNicknameInput(e) {
    this.setData({ nickname: e.detail.value });
  },

  /**
   * 输入手机号
   */
  onPhoneInput(e) {
    this.setData({ phone: e.detail.value });
  },

  /**
   * 提交注册
   */
  onSubmit() {
    const { nickname, phone } = this.data;
    if (!nickname.trim()) {
      wx.showToast({ title: '请输入昵称', icon: 'none' });
      return;
    }
    if (!phone.trim() || !/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: '请输入正确的手机号', icon: 'none' });
      return;
    }

    this.setData({ submitting: true });

    // 获取微信 code 并注册
    wx.login({
      success: (loginRes) => {
        if (!loginRes.code) {
          this.setData({ submitting: false });
          wx.showToast({ title: '获取微信授权失败', icon: 'none' });
          return;
        }
        wx.request({
          url: `${app.globalData.apiBase}/api/auth/register`,
          method: 'POST',
          data: {
            code: loginRes.code,
            nickname: nickname.trim(),
            avatar: this.data.avatar,
            phone: phone.trim()
          },
          success: (res) => {
            this.setData({ submitting: false });
            if (res.statusCode === 200 && res.data?.code === 200) {
              const data = res.data.data || {};
              if (data.token) {
                wx.setStorageSync('token', data.token);
                if (data.refreshToken) {
                  wx.setStorageSync('refreshToken', data.refreshToken);
                }
                app.globalData.isLoggedIn = true;
                wx.showToast({ title: '注册成功', icon: 'success' });
                setTimeout(() => {
                  wx.reLaunch({ url: '/pages/menu/index' });
                }, 1000);
              } else {
                wx.showToast({ title: '注册失败', icon: 'none' });
              }
            } else {
              wx.showToast({
                title: res.data?.message || '注册失败',
                icon: 'none'
              });
            }
          },
          fail: () => {
            this.setData({ submitting: false });
            wx.showToast({ title: '网络异常', icon: 'none' });
          }
        });
      },
      fail: () => {
        this.setData({ submitting: false });
        wx.showToast({ title: '微信登录失败', icon: 'none' });
      }
    });
  }
});
