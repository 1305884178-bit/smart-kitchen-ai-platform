package com.smartkitchen.config;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

// 由于只需维护单个连接，移除 CopyOnWriteArraySet 的导入

@Component
public class KitchenBoardWebSocketHandler extends TextWebSocketHandler {

    private static volatile WebSocketSession kitchenBoardSession = null;

    /**
     * 连接建立后的处理逻辑
     * @param session WebSocket会话对象
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 如果已有旧连接，则关闭它以保证单一连接
        if (kitchenBoardSession != null && kitchenBoardSession.isOpen()) {
            kitchenBoardSession.close(CloseStatus.NORMAL);
        }
        kitchenBoardSession = session;
        // 连接建立后，可考虑在此处拉取一次 HTTP 快照推送
    }

    /**
     * 接收到文本消息时的处理逻辑
     * @param session WebSocket会话对象
     * @param message 接收到的文本消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 接收到消息时的处理
    }

    /**
     * 连接关闭后的处理逻辑
     * @param session WebSocket会话对象
     * @param status 关闭状态信息
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        if (kitchenBoardSession != null && kitchenBoardSession.getId().equals(session.getId())) {
            kitchenBoardSession = null;
        }
    }

    /**
     * 发送消息到厨房看板客户端
     * @param message 待发送的文本消息
     */
    public void sendMessage(String message) {
        if (kitchenBoardSession != null && kitchenBoardSession.isOpen()) {
            try {
                kitchenBoardSession.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
