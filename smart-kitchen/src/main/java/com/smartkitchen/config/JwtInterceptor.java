package com.smartkitchen.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenStore tokenStore;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
                Claims claims = jwtUtil.parseToken(token);
                if (!jwtUtil.isAccessToken(claims)) {
                    return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "请使用 Access Token");
                }
                String jti = claims.getId();
                if (tokenStore.isBlacklisted(jti)) {
                    return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或Token已过期");
                }
                Long userId = claims.get("userId", Long.class);
                String role = claims.get("role", String.class);
                if (userId != null && tokenStore.isUserRevoked(userId, jwtUtil.issuedAtMillis(claims))) {
                    return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或Token已过期");
                }
                if (AdminPathMatcher.requiresAdmin(request.getRequestURI()) && !"ADMIN".equals(role)) {
                    return reject(response, HttpServletResponse.SC_FORBIDDEN, "无权限访问");
                }

                UserContext.setUserId(userId);
                UserContext.setRole(role);
                return true;
            }
        }

        return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或Token已过期");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
        return false;
    }
}
