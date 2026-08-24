package com.smartkitchen.config;

/**
 * 需要 ADMIN 角色的 HTTP 路径（与厨房看板 WebSocket 的角色校验对齐）。
 */
public final class AdminPathMatcher {

    private AdminPathMatcher() {
    }

    public static boolean requiresAdmin(String uri) {
        if (uri == null) {
            return false;
        }
        if (uri.startsWith("/api/admin/")) {
            return true;
        }
        if (uri.startsWith("/api/kitchen-board/")) {
            return true;
        }
        if ("/api/order/admin-list".equals(uri) || uri.startsWith("/api/order/admin-detail/")) {
            return true;
        }
        return uri.matches("/api/order/\\d+/(cancel|complete)");
    }
}
