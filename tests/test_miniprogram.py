#!/usr/bin/env python3
"""
Phase 6 Step 4 测试脚本 — C端微信小程序页面验证
检查所有页面文件的完整性和代码规范
"""
import os
import json
import sys

MINIPROGRAM_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'miniprogram')

passed = 0
failed = 0
errors = []

def test(name, condition, detail=''):
    global passed, failed
    if condition:
        passed += 1
        print(f'  ✓ {name}')
    else:
        failed += 1
        msg = f'  ✗ {name}' + (f' — {detail}' if detail else '')
        print(msg)
        errors.append(msg)

# ===== 1. 项目配置文件检查 =====
print('\n[1] 项目配置文件')
app_json_path = os.path.join(MINIPROGRAM_DIR, 'app.json')
test('app.json 存在', os.path.exists(app_json_path))
if os.path.exists(app_json_path):
    with open(app_json_path, 'r') as f:
        app_json = json.load(f)
    test('app.json pages 非空', len(app_json.get('pages', [])) == 8)
    test('app.json tabBar 有3个Tab', len(app_json['tabBar']['list']) == 3)
    tabs = [t['pagePath'] for t in app_json['tabBar']['list']]
    test('TabBar 包含菜单', 'pages/menu/index' in tabs)
    test('TabBar 包含订单', 'pages/my-orders/index' in tabs)
    test('TabBar 包含AI客服', 'pages/ai-chat/index' in tabs)

# ===== 2. 页面文件完整性检查 =====
print('\n[2] 页面文件完整性')
pages = [
    'menu', 'dish-detail', 'order', 'order-detail',
    'my-orders', 'review', 'ai-chat', 'register'
]
required_files = ['index.js', 'index.wxml', 'index.wxss']
for page in pages:
    page_dir = os.path.join(MINIPROGRAM_DIR, 'pages', page)
    for fname in required_files:
        fpath = os.path.join(page_dir, fname)
        test(f'{page}/{fname}', os.path.exists(fpath), f'文件缺失: {fpath}')

# ===== 3. 工具类文件检查 =====
print('\n[3] 工具类文件')
utils_dir = os.path.join(MINIPROGRAM_DIR, 'utils')
for util in ['request.js', 'sse.js', 'cart.js']:
    fpath = os.path.join(utils_dir, util)
    test(f'utils/{util}', os.path.exists(fpath), f'文件缺失: {fpath}')

# ===== 4. app.js 功能检查 =====
print('\n[4] app.js 功能')
app_js_path = os.path.join(MINIPROGRAM_DIR, 'app.js')
with open(app_js_path, 'r') as f:
    app_js = f.read()
test('app.js 包含 doWxLogin', 'doWxLogin' in app_js)
test('app.js 包含 checkLogin', 'checkLogin' in app_js)
test('app.js 包含 wx.login', 'wx.login' in app_js)
test('app.js 包含 register 跳转', '/pages/register/index' in app_js)

# ===== 5. cart.js 功能检查 =====
print('\n[5] cart.js 功能')
cart_js_path = os.path.join(MINIPROGRAM_DIR, 'utils', 'cart.js')
with open(cart_js_path, 'r') as f:
    cart_js = f.read()
cart_funcs = ['getCartItems', 'addToCart', 'updateQuantity', 'removeFromCart',
              'clearCart', 'getTotalCount', 'getTotalAmount']
for func in cart_funcs:
    test(f'cart.js 导出 {func}', f'module.exports' in cart_js and func in cart_js)

# ===== 6. 页面 JS 逻辑检查 =====
print('\n[6] 页面 JS 逻辑')

# register
with open(os.path.join(MINIPROGRAM_DIR, 'pages/register', 'index.js'), 'r') as f:
    register_js = f.read()
test('register: 包含 onChooseAvatar', 'onChooseAvatar' in register_js)
test('register: 包含 onSubmit', 'onSubmit' in register_js)
test('register: 手机号校验正则', '/1\\d{10}/' in register_js or '/^1\\d{10}$/' in register_js)

# menu
with open(os.path.join(MINIPROGRAM_DIR, 'pages/menu', 'index.js'), 'r') as f:
    menu_js = f.read()
test('menu: 包含 _loadCategories', '_loadCategories' in menu_js)
test('menu: 包含 _loadDishes', '_loadDishes' in menu_js)
test('menu: 包含 onAddToCart', 'onAddToCart' in menu_js)

# dish-detail
with open(os.path.join(MINIPROGRAM_DIR, 'pages/dish-detail', 'index.js'), 'r') as f:
    detail_js = f.read()
test('dish-detail: 包含 _loadDishDetail', '_loadDishDetail' in detail_js)
test('dish-detail: 包含 onAddToCart', 'onAddToCart' in detail_js)

# order
with open(os.path.join(MINIPROGRAM_DIR, 'pages/order', 'index.js'), 'r') as f:
    order_js = f.read()
test('order: 包含 _loadSeats', '_loadSeats' in order_js)
test('order: 包含 onSubmit', 'onSubmit' in order_js)
test('order: 包含 cart.clearCart', 'clearCart' in order_js)
test('order: 调用 /api/order/submit', '/api/order/submit' in order_js)

# order-detail
with open(os.path.join(MINIPROGRAM_DIR, 'pages/order-detail', 'index.js'), 'r') as f:
    od_js = f.read()
test('order-detail: 包含 _loadOrderDetail', '_loadOrderDetail' in od_js)
test('order-detail: 包含 onAction', 'onAction' in od_js)
test('order-detail: 包含 _onPay', '_onPay' in od_js)

# my-orders
with open(os.path.join(MINIPROGRAM_DIR, 'pages/my-orders', 'index.js'), 'r') as f:
    mo_js = f.read()
test('my-orders: 包含 _loadOrders', '_loadOrders' in mo_js)
test('my-orders: 包含 onReachBottom', 'onReachBottom' in mo_js)
test('my-orders: 包含 onOrderTap', 'onOrderTap' in mo_js)

# review
with open(os.path.join(MINIPROGRAM_DIR, 'pages/review', 'index.js'), 'r') as f:
    review_js = f.read()
test('review: 包含 onStarTap', 'onStarTap' in review_js)
test('review: 包含 onSubmit', 'onSubmit' in review_js)
test('review: 调用 /api/review/submit', '/api/review/submit' in review_js)

# ai-chat
with open(os.path.join(MINIPROGRAM_DIR, 'pages/ai-chat', 'index.js'), 'r') as f:
    ai_js = f.read()
test('ai-chat: 包含 createSSE', 'createSSE' in ai_js)
test('ai-chat: 包含 onSend', 'onSend' in ai_js)
test('ai-chat: 包含 onInput', 'onInput' in ai_js)

# ===== 7. SSE 工具检查 =====
print('\n[7] SSE 工具')
sse_js_path = os.path.join(MINIPROGRAM_DIR, 'utils', 'sse.js')
with open(sse_js_path, 'r') as f:
    sse_js = f.read()
test('sse.js: 导出 createSSE', 'module.exports' in sse_js and 'createSSE' in sse_js)
test('sse.js: 包含 enableChunked', 'enableChunked' in sse_js)
test('sse.js: 包含 降级 POST', '降级' in sse_js or 'else' in sse_js)

# ===== 8. 图片资源检查 =====
print('\n[8] 图片资源')
img_dir = os.path.join(MINIPROGRAM_DIR, 'images')
tab_icons = ['tab-menu.png', 'tab-menu-active.png', 'tab-order.png',
             'tab-order-active.png', 'tab-ai.png', 'tab-ai-active.png']
for icon in tab_icons:
    fpath = os.path.join(img_dir, icon)
    test(f'images/{icon}', os.path.exists(fpath), f'图片缺失: {fpath}')

# ===== 结果总结 =====
print(f'\n{"="*40}')
print(f'测试结果: {passed} 通过, {failed} 失败')
if failed > 0:
    print('失败项:')
    for e in errors:
        print(e)
    sys.exit(1)
else:
    print('全部测试通过!')
    sys.exit(0)
