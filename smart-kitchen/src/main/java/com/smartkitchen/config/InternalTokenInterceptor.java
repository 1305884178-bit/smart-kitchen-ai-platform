package com.smartkitchen.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 服务间内部接口（/api/proxy/**）鉴权拦截器。
 *
 * 语义与 Python 侧 utils/auth.verify_internal_token 对齐：
 * - 未配置 smart-kitchen.python-service.internal-token 时全部放行（本地联调默认）；
 * - 一旦配置，必须携带 Authorization: Bearer <internal-token>，否则 401。
 *
 * 该路径在 WebMvcConfig 中仍排除用户 JWT（顾客/管理员 token 不应打到 proxy），
 * 由本拦截器接管服务间鉴权。
 */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    @Value("${smart-kitchen.python-service.internal-token:}")
    private String internalToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 未配置内部 token：与 Python 未配 AI_INTERNAL_TOKEN 行为一致，放行便于本地联调
        if (internalToken == null || internalToken.isEmpty()) {
            return true;
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            // 常量时间比较，与 Python hmac.compare_digest 对齐
            if (MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8),
                    internalToken.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"内部 token 缺失或无效\"}");
        return false;
    }
}
