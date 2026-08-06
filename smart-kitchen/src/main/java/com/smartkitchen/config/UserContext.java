package com.smartkitchen.config;

/**
 * 基于 ThreadLocal 的用户上下文工具类
 * 用于在同一个请求线程内传递当前登录用户信息（userId、role）
 * 请求结束后必须调用 clear() 清理，防止内存泄漏和线程复用时的数据串扰
 */
public class UserContext {

    private static final ThreadLocal<Long> userIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> roleHolder = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        userIdHolder.set(userId);
    }

    public static Long getUserId() {
        return userIdHolder.get();
    }

    public static void setRole(String role) {
        roleHolder.set(role);
    }

    public static String getRole() {
        return roleHolder.get();
    }

    /**
     * 清理 ThreadLocal，必须在请求结束后调用（如拦截器的 afterCompletion）
     */
    public static void clear() {
        userIdHolder.remove();
        roleHolder.remove();
    }
}
