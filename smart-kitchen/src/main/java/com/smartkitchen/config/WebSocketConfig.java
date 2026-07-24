package com.smartkitchen.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private WebSocketHandler kitchenBoardWebSocketHandler;

    @Autowired
    private CustomerWebSocketHandler customerWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(kitchenBoardWebSocketHandler, "/ws/kitchen-board")
                .setAllowedOrigins("*");
        registry.addHandler(customerWebSocketHandler, "/ws/customer")
                .setAllowedOrigins("*");
    }
}
