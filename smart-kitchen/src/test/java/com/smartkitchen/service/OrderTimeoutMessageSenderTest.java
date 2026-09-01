package com.smartkitchen.service;

import com.smartkitchen.entity.MqSendFail;
import com.smartkitchen.mapper.MqSendFailMapper;
import com.smartkitchen.mq.OrderTimeoutMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 订单超时延迟消息发送器单元测试（纯Mock）
 *
 * 覆盖：afterCommit 语义外的直接发送路径（无事务时降级直发）、x-delay 头、
 * convertAndSend 当场抛错落失败表、confirm nack 异步重试、重试 3 次仍失败落表停止。
 */
@ExtendWith(MockitoExtension.class)
public class OrderTimeoutMessageSenderTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private MqSendFailMapper mqSendFailMapper;

    @InjectMocks
    private OrderTimeoutMessageSender sender;

    @BeforeEach
    public void setup() {
        ReflectionTestUtils.setField(sender, "payTimeoutMs", 900000L);
        sender.init();
        verify(rabbitTemplate).setConfirmCallback(sender);
    }

    /**
     * 测试：正常发送 → 延迟交换机 + rk=order.timeout + x-delay 头 + CorrelationData 携带 orderId
     */
    @Test
    public void testSend_withDelayHeaderAndCorrelationData() {
        sender.sendAfterCommit(100L);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> cdCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(eq("order.delay.exchange"), eq("order.timeout"),
                messageCaptor.capture(), cdCaptor.capture());

        assertEquals("100", new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8));
        assertEquals(900000L, messageCaptor.getValue().getMessageProperties().getHeaders().get("x-delay"));
        assertEquals("100:0", cdCaptor.getValue().getId());
        verify(mqSendFailMapper, never()).insert(any(MqSendFail.class));
    }

    /**
     * 测试：convertAndSend 当场抛错 → 写 oms_mq_send_fail，不向上抛异常（下单接口不受影响）
     */
    @Test
    public void testSend_immediateException_recordsFailAndDoesNotThrow() {
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertDoesNotThrow(() -> sender.sendAfterCommit(200L));

        ArgumentCaptor<MqSendFail> captor = ArgumentCaptor.forClass(MqSendFail.class);
        verify(mqSendFailMapper).insert(captor.capture());
        assertEquals(200L, captor.getValue().getOrderId());
        assertEquals(0, captor.getValue().getRetryCount());
    }

    /**
     * 测试：confirm ack → 仅日志，不重试、不落表
     */
    @Test
    public void testConfirm_ack_noop() {
        sender.confirm(new CorrelationData("300:0"), true, null);

        verifyNoMoreInteractions(rabbitTemplate);
        verify(mqSendFailMapper, never()).insert(any(MqSendFail.class));
    }

    /**
     * 测试：confirm nack → 异步重试（不占调用线程），重试消息再次发出
     */
    @Test
    public void testConfirm_nack_retriesAsync() {
        sender.sendAfterCommit(400L);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        sender.confirm(new CorrelationData("400:0"), false, "nacked by broker");

        // 首次重试在 0.5s 后由线程池触发
        verify(rabbitTemplate, timeout(5000).times(2))
                .convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verify(mqSendFailMapper, never()).insert(any(MqSendFail.class));
    }

    /**
     * 测试：重试 3 次仍 nack → 写 oms_mq_send_fail + 停止重试
     */
    @Test
    public void testConfirm_nackExhausted_recordsFail() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return 1;
        }).when(mqSendFailMapper).insert(any(MqSendFail.class));

        // attempt=3 的 nack：已达上限，落表并停止
        sender.confirm(new CorrelationData("500:3"), false, "still nacked");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "重试耗尽后应写入失败表");
        ArgumentCaptor<MqSendFail> captor = ArgumentCaptor.forClass(MqSendFail.class);
        verify(mqSendFailMapper).insert(captor.capture());
        assertEquals(500L, captor.getValue().getOrderId());
        assertEquals(3, captor.getValue().getRetryCount());
        // 不再发起新的重试
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    /**
     * 测试：RabbitTemplate 缺失（未配置 MQ）→ 直接落失败表，不抛异常
     */
    @Test
    public void testSend_rabbitTemplateMissing_recordsFail() {
        OrderTimeoutMessageSender noMqSender = new OrderTimeoutMessageSender();
        ReflectionTestUtils.setField(noMqSender, "rabbitTemplate", null);
        ReflectionTestUtils.setField(noMqSender, "mqSendFailMapper", mqSendFailMapper);
        ReflectionTestUtils.setField(noMqSender, "payTimeoutMs", 900000L);

        assertDoesNotThrow(() -> noMqSender.sendAfterCommit(600L));
        verify(mqSendFailMapper).insert(argThat((MqSendFail f) -> f.getOrderId().equals(600L)));
    }
}
