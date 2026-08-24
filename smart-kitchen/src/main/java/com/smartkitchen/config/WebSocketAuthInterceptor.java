package com.smartkitchen.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器
 *
 * 握手阶段校验 URL ?token= 参数中的 JWT：
 * - /ws/kitchen-board：仅 ADMIN 角色可连接（厨房看板为 B 端内部页面）；
 * - /ws/customer：任意合法 token 可连接，userId 由 token 解析写入会话属性，
 *   取代原先"客户端自报 userId"的方式，防止冒充他人收听出餐通知。
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    /** 握手成功后写入 attributes 的键 */
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_ROLE = "role";

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenStore tokenStore;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return false;
        }

        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        String token = httpRequest.getParameter("token");
        if (token == null || token.isBlank() || !jwtUtil.validateToken(token)) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return false;
        }

        Claims claims = jwtUtil.parseToken(token);
        if (!jwtUtil.isAccessToken(claims) || tokenStore.isBlacklisted(claims.getId())) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return false;
        }
        Long userId = claims.get("userId", Long.class);
        String role = claims.get("role", String.class);
        if (userId == null || role == null) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (tokenStore.isUserRevoked(userId, jwtUtil.issuedAtMillis(claims))) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 厨房看板仅允许管理员接入
        String path = request.getURI().getPath();
        if (path != null && path.endsWith("/ws/kitchen-board") && !"ADMIN".equals(role)) {
            reject(response, HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_ROLE, role);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    private void reject(ServerHttpResponse response, HttpStatus status) {
        if (response instanceof ServletServerHttpResponse servletResponse) {
            servletResponse.setStatusCode(status);
        }
    }
}
