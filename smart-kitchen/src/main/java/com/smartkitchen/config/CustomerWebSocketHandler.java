package com.smartkitchen.config;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class CustomerWebSocketHandler extends TextWebSocketHandler {

    // 存储用户ID和对应的Session
    private static final ConcurrentHashMap<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // userId 由握手拦截器从 JWT 解析后写入会话属性，不再信任客户端自报
        Object userId = session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID);
        if (userId != null) {
            userSessions.put(String.valueOf(userId), session);
        } else {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("missing authenticated userId"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        userSessions.values().remove(session);
    }

    public void sendMessageToUser(Long userId, String message) {
        WebSocketSession session = userSessions.get(String.valueOf(userId));
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}