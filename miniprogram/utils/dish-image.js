/** 菜品图统一兜底：小程序包内资源不依赖网络域名配置。 */
const DEFAULT_DISH_IMAGE = '/images/default-dish.png';

/**
 * 仅接受 HTTPS 图片地址。空值、历史相对路径及 HTTP 地址统一使用本地默认图，
 * 避免小程序因图片域名校验或未托管的相对路径而出现空白。
 */
function resolveDishImage(image) {
  const url = typeof image === 'string' ? image.trim() : '';
  return /^https:\/\//i.test(url) ? url : DEFAULT_DISH_IMAGE;
}

module.exports = { DEFAULT_DISH_IMAGE, resolveDishImage };
