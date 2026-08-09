// pages/review/index.js
const request = require('../../utils/request');

Page({
  data: {
    orderId: null,
    score: 0,
    comment: '',
    submitting: false
  },

  onLoad(options) {
    const orderId = options.orderId;
    if (orderId) {
      this.setData({ orderId });
    }
  },

  /**
   * 点击星级
   */
  onStarTap(e) {
    const score = e.currentTarget.dataset.score;
    this.setData({ score });
  },

  /**
   * 输入评价
   */
  onCommentInput(e) {
    this.setData({ comment: e.detail.value });
  },

  /**
   * 提交评价
   */
  onSubmit() {
    const { orderId, score, comment } = this.data;
    if (!orderId) {
      wx.showToast({ title: '订单信息缺失', icon: 'none' });
      return;
    }
    if (score === 0) {
      wx.showToast({ title: '请选择评分', icon: 'none' });
      return;
    }

    this.setData({ submitting: true });

    request.post('/api/review/submit', {
      orderId: parseInt(orderId),
      score: score,
      comment: comment
    })
      .then(() => {
        wx.showToast({ title: '评价成功', icon: 'success' });
        setTimeout(() => {
          wx.navigateBack();
        }, 1000);
      })
      .catch(() => {
        this.setData({ submitting: false });
      });
  }
});
