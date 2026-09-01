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

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

/**
 * 订单支付超时消费者单元测试（纯Mock，不加载Spring上下文）
 *
 * 先付后做口径：
 * - 未支付 ORDERED → 取消（cancelOrderForTimeout）并 ACK
 * - 已支付（pay_time 非空）/ PAID / SERVED / CANCELLED / 不存在 → ACK 跳过，不取消
 * - 超时永不取消 SERVED
 * - 消费异常 → basicNack(requeue=false) 进 DLQ
 */
@ExtendWith(MockitoExtension.class)
public class OrderTimeoutConsumerTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderTimeoutConsumer orderTimeoutConsumer;

    private Channel mockChannel;

    @BeforeEach
    public void setup() {
        mockChannel = mock(Channel.class);
    }

    /**
     * 测试：ORDERED 且未支付 → 调用 cancelOrderForTimeout，然后 ACK
     */
    @Test
    public void testHandleOrderTimeout_orderedUnpaid_shouldCancel() throws Exception {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(OrderStatusEnum.ORDERED.getCode());

        when(orderService.getById(1L)).thenReturn(order);

        orderTimeoutConsumer.handleOrderTimeout("1", mockChannel, 10L);

        verify(orderService).cancelOrderForTimeout(1L);
        verify(mockChannel).basicAck(10L, false);
    }

    /**
     * 测试：ORDERED 但已支付（pay_time 非空）→ 跳过取消，ACK
     */
    @Test
    public void testHandleOrderTimeout_orderedButPaid_shouldSkip() throws Exception {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(OrderStatusEnum.ORDERED.getCode());
        order.setPayTime(LocalDateTime.now());

        when(orderService.getById(1L)).thenReturn(order);

        orderTimeoutConsumer.handleOrderTimeout("1", mockChannel, 15L);

        verify(orderService, never()).cancelOrderForTimeout(anyLong());
        verify(mockChannel).basicAck(15L, false);
    }

    /**
     * 测试：SERVED 状态 → 超时永不取消，ACK 跳过
     */
    @Test
    public void testHandleOrderTimeout_servedOrder_shouldSkip() throws Exception {
        Order order = new Order();
        order.setId(2L);
        order.setStatus(OrderStatusEnum.SERVED.getCode());

        when(orderService.getById(2L)).thenReturn(order);

        orderTimeoutConsumer.handleOrderTimeout("2", mockChannel, 20L);

        verify(orderService, never()).cancelOrderForTimeout(anyLong());
        verify(mockChannel).basicAck(20L, false);
    }

    /**
     * 测试：PAID 状态 → 跳过，不取消
     */
    @Test
    public void testHandleOrderTimeout_paidOrder_shouldSkip() throws Exception {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(OrderStatusEnum.PAID.getCode());

        when(orderService.getById(1L)).thenReturn(order);

        orderTimeoutConsumer.handleOrderTimeout("1", mockChannel, 30L);

        verify(orderService, never()).cancelOrderForTimeout(anyLong());
        verify(mockChannel).basicAck(30L, false);
    }

    /**
     * 测试：CANCELLED 状态 → 跳过
     */
    @Test
    public void testHandleOrderTimeout_cancelledOrder_shouldSkip() throws Exception {
        Order order = new Order();
        order.setId(2L);
        order.setStatus(OrderStatusEnum.CANCELLED.getCode());

        when(orderService.getById(2L)).thenReturn(order);

        orderTimeoutConsumer.handleOrderTimeout("2", mockChannel, 40L);

        verify(orderService, never()).cancelOrderForTimeout(anyLong());
        verify(mockChannel).basicAck(40L, false);
    }

    /**
     * 测试：订单不存在 → 记录日志后ACK，不抛异常
     */
    @Test
    public void testHandleOrderTimeout_nonexistentOrder_shouldAck() throws Exception {
        when(orderService.getById(99999L)).thenReturn(null);

        orderTimeoutConsumer.handleOrderTimeout("99999", mockChannel, 50L);

        verify(orderService, never()).cancelOrderForTimeout(anyLong());
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
}
