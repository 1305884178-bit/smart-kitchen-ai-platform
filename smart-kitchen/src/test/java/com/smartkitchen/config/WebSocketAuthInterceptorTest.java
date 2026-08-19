package com.smartkitchen.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket 握手鉴权测试
 *
 * 覆盖：合法 token 放行并写入 userId / 缺 token 拒绝 / 非法 token 拒绝 /
 * 非 ADMIN 连厨房看板拒绝 / 顾客端会话按 token 中的 userId 绑定。
 */
@SpringBootTest
@ActiveProfiles("test")
public class WebSocketAuthInterceptorTest {

    @Autowired
    private WebSocketAuthInterceptor interceptor;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private CustomerWebSocketHandler customerWebSocketHandler;

    private ServletServerHttpRequest request(String uri, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        if (token != null) {
            req.setParameter("token", token);
        }
        return new ServletServerHttpRequest(req);
    }

    @Test
    public void testKitchenBoard_validAdminToken_passes() {
        String token = jwtUtil.generateToken(1000L, "ADMIN", "admin_openid");
        MockHttpServletResponse rawResp = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(
                request("/ws/kitchen-board", token),
                new ServletServerHttpResponse(rawResp),
                mock(WebSocketHandler.class),
                attributes);

        assertTrue(result, "ADMIN 合法 token 应放行");
        assertEquals(1000L, attributes.get(WebSocketAuthInterceptor.ATTR_USER_ID));
        assertEquals("ADMIN", attributes.get(WebSocketAuthInterceptor.ATTR_ROLE));
    }

    @Test
    public void testKitchenBoard_customerToken_forbidden() {
        String token = jwtUtil.generateToken(1001L, "CUSTOMER", "openid_1001");
        MockHttpServletResponse rawResp = new MockHttpServletResponse();

        boolean result = interceptor.beforeHandshake(
                request("/ws/kitchen-board", token),
                new ServletServerHttpResponse(rawResp),
                mock(WebSocketHandler.class),
                new HashMap<>());

        assertFalse(result, "顾客角色不应接入厨房看板");
        assertEquals(HttpStatus.FORBIDDEN.value(), rawResp.getStatus());
    }

    @Test
    public void testKitchenBoard_missingToken_unauthorized() {
        MockHttpServletResponse rawResp = new MockHttpServletResponse();

        boolean result = interceptor.beforeHandshake(
                request("/ws/kitchen-board", null),
                new ServletServerHttpResponse(rawResp),
                mock(WebSocketHandler.class),
                new HashMap<>());

        assertFalse(result);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), rawResp.getStatus());
    }

    @Test
    public void testKitchenBoard_invalidToken_unauthorized() {
        MockHttpServletResponse rawResp = new MockHttpServletResponse();

        boolean result = interceptor.beforeHandshake(
                request("/ws/kitchen-board", "not.a.valid.token"),
                new ServletServerHttpResponse(rawResp),
                mock(WebSocketHandler.class),
                new HashMap<>());

        assertFalse(result);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), rawResp.getStatus());
    }

    @Test
    public void testCustomer_validToken_passesAndBindsUserId() throws Exception {
        String token = jwtUtil.generateToken(1001L, "CUSTOMER", "openid_1001");
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(
                request("/ws/customer", token),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes);

        assertTrue(result, "顾客合法 token 应放行");
        assertEquals(1001L, attributes.get(WebSocketAuthInterceptor.ATTR_USER_ID));

        // 会话绑定取自握手属性而非客户端 query 参数
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("session-1");
        customerWebSocketHandler.afterConnectionEstablished(session);
        verify(session, never()).close(org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void testCustomerSession_missingUserIdAttr_closesConnection() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());

        customerWebSocketHandler.afterConnectionEstablished(session);

        verify(session).close(org.mockito.ArgumentMatchers.any());
        assertNull(session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID));
    }
}
