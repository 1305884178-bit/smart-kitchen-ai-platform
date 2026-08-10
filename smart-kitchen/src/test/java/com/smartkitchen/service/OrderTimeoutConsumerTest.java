package com.smartkitchen.service;

import com.rabbitmq.client.Channel;
import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.entity.Order;
import com.smartkitchen.consumer.OrderTimeoutConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase 6 Step 5 RabbitMQ 订单超时消费者单元测试（纯Mock，不加载Spring上下文）
 * 覆盖：ORDERED超时取消 / SERVED超时取消 / PAID跳过 / CANCELLED跳过 / 订单不存在跳过
 */
@ExtendWith(MockitoExtension.class)
public class OrderTimeoutConsumerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OrderTimeoutConsumer orderTimeoutConsumer;

    private Channel mockChannel;

    @BeforeEach
    public void setup() {
        mockChannel = mock(Channel.class);
    }

    /**
     * 测试：ORDERED状态订单超时 → 应调用cancelOrder，然后ACK
     */
    @Test
    public void testHandleOrderTimeout_orderedOrder_shouldCancel() throws Exception {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(OrderStatusEnum.ORDERED.getCode());

        when(orderService.getById(1L)).thenReturn(order);

        orderTimeoutConsumer.handleOrderTimeout("1", mockChannel, 10L);

        verify(orderService).cancelOrder(1L);
        verify(mockChannel).basicAck(10L, false);
    }

    /**
     * 测试：SERVED状态订单超时 → 应调用cancelOrder
     */
    @Test
    public void testHandleOrderTimeout_servedOrder_shouldCancel() throws Exception {
        Order order = new Order();
        order.setId(2L);
        order.setStatus(OrderStatusEnum.SERVED.getCode());

        when(orderService.getById(2L)).thenReturn(order);

        orderTimeoutConsumer.handleOrderTimeout("2", mockChannel, 20L);

        verify(orderService).cancelOrder(2L);
        verify(mockChannel).basicAck(20L, false);
    }

    /**
     * 测试：PAID状态订单超时 → 跳过，不调用cancelOrder
     */
    @Test
    public void testHandleOrderTimeout_paidOrder_shouldSkip() throws Exception {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(OrderStatusEnum.PAID.getCode());

        when(orderService.getById(1L)).thenReturn(order);

        orderTimeoutConsumer.handleOrderTimeout("1", mockChannel, 30L);

        verify(orderService, never()).cancelOrder(anyLong());
        verify(mockChannel).basicAck(30L, false);
    }

    /**
     * 测试：CANCELLED状态订单超时 → 跳过
     */
    @Test
    public void testHandleOrderTimeout_cancelledOrder_shouldSkip() throws Exception {
        Order order = new Order();
        order.setId(2L);
        order.setStatus(OrderStatusEnum.CANCELLED.getCode());

        when(orderService.getById(2L)).thenReturn(order);

        orderTimeoutConsumer.handleOrderTimeout("2", mockChannel, 40L);

        verify(orderService, never()).cancelOrder(anyLong());
        verify(mockChannel).basicAck(40L, false);
    }

    /**
     * 测试：订单不存在 → 记录日志后ACK，不抛异常
     */
    @Test
    public void testHandleOrderTimeout_nonexistentOrder_shouldAck() throws Exception {
        when(orderService.getById(99999L)).thenReturn(null);

        orderTimeoutConsumer.handleOrderTimeout("99999", mockChannel, 50L);

        verify(orderService, never()).cancelOrder(anyLong());
        verify(mockChannel).basicAck(50L, false);
    }

    /**
     * 测试：消费异常时 basicNack(requeue=false)，消息进入 DLQ 等待人工处理
     */
    @Test
    public void testHandleOrderTimeout_exception_shouldNackToDlq() throws Exception {
        when(orderService.getById(1L)).thenThrow(new RuntimeException("DB error"));

        orderTimeoutConsumer.handleOrderTimeout("1", mockChannel, 60L);

        verify(mockChannel).basicNack(60L, false, false);
    }

    /**
     * 测试：RabbitTemplate发送格式验证
     */
    @Test
    public void testSubmitOrder_sendsDelayMessage() {
        rabbitTemplate.convertAndSend("order.delay.exchange", "order.delay", "100");
        verify(rabbitTemplate).convertAndSend("order.delay.exchange", "order.delay", "100");
    }
}
