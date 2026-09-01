package com.smartkitchen.mq;

import com.smartkitchen.config.RabbitMQConfig;
import com.smartkitchen.entity.MqSendFail;
import com.smartkitchen.mapper.MqSendFailMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 订单支付超时延迟消息发送器（先付后做：15 分钟未支付自动取消）
 *
 * 可靠性策略（不堵下单接口、不加本地消息表）：
 * 1. 业务事务先提交，消息在 afterCommit 之后才发；MQ 异常不回滚订单/库存；
 * 2. convertAndSend 当场抛错：记日志 + 写 oms_mq_send_fail，接口仍返回成功；
 * 3. publisher confirm：ack 仅 debug 日志；nack 由独立线程池异步重试最多 3 次
 *    （间隔 0.5s / 1s / 2s，不占用 HTTP 线程），仍失败写 oms_mq_send_fail + error 日志；
 * 4. 最终兜底：未支付超时扫表任务（OrderTimeoutScanTask）保证关单。
 */
@Component
public class OrderTimeoutMessageSender implements RabbitTemplate.ConfirmCallback {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutMessageSender.class);

    /** confirm nack 最大重试次数（不含首发） */
    private static final int MAX_RETRY = 3;
    /** 各次重试前的等待毫秒：第 1/2/3 次重试分别等 0.5s / 1s / 2s */
    private static final long[] RETRY_BACKOFF_MS = {500L, 1000L, 2000L};

    @Autowired(required = false)
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MqSendFailMapper mqSendFailMapper;

    /** 支付窗口毫秒数，写入消息头 x-delay（默认 15 分钟） */
    @Value("${smart-kitchen.order.pay-timeout-ms:900000}")
    private long payTimeoutMs;

    /** 异步重试线程池：confirm 回调只负责投递任务，禁止占用 HTTP/IO 线程 */
    private final ScheduledExecutorService retryExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "order-timeout-msg-retry");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        if (rabbitTemplate != null) {
            rabbitTemplate.setConfirmCallback(this);
        }
    }

    @PreDestroy
    public void destroy() {
        retryExecutor.shutdownNow();
    }

    /**
     * 事务提交后发送该订单的支付超时延迟消息。
     * 必须在下单/加菜事务内调用：注册 afterCommit 回调，提交成功才真正发送；
     * 若当前无事务（理论上不会发生），降级为直接发送。
     */
    public void sendAfterCommit(Long orderId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(orderId, 0);
                }
            });
        } else {
            send(orderId, 0);
        }
    }

    /**
     * 发送延迟消息（body=orderId 字符串，header x-delay=支付窗口毫秒）。
     *
     * @param orderId 订单 ID
     * @param attempt 第几次重试（0 表示首发；用于 CorrelationData 追踪与重试上限判断）
     */
    private void send(Long orderId, int attempt) {
        if (rabbitTemplate == null) {
            recordFail(orderId, "RabbitTemplate 不可用（未配置 RabbitMQ）", attempt);
            return;
        }
        try {
            Message message = MessageBuilder.withBody(orderId.toString().getBytes(StandardCharsets.UTF_8))
                    .setContentType("text/plain")
                    .setHeader("x-delay", payTimeoutMs)
                    .build();
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ORDER_DELAY_EXCHANGE,
                    RabbitMQConfig.ORDER_TIMEOUT_ROUTING_KEY,
                    message,
                    new CorrelationData(correlationId(orderId, attempt)));
        } catch (Exception e) {
            // 当场抛错：不回滚已提交订单；首发直接落失败表，重试中的失败走统一重试裁决
            log.error("订单超时延迟消息发送异常，orderId={}, attempt={}", orderId, attempt, e);
            if (attempt == 0) {
                recordFail(orderId, "convertAndSend 异常: " + e.getMessage(), attempt);
            } else {
                onSendFailure(orderId, attempt, "convertAndSend 异常: " + e.getMessage());
            }
        }
    }

    /**
     * publisher confirm 回调：ack 仅记录；nack 投递到异步线程池重试，最多 3 次。
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        String cid = correlationData == null ? null : correlationData.getId();
        if (ack) {
            log.debug("订单超时延迟消息 confirm ack，correlationId={}", cid);
            return;
        }
        Long orderId = parseOrderId(cid);
        int attempt = parseAttempt(cid);
        log.warn("订单超时延迟消息 confirm nack，orderId={}, attempt={}, cause={}", orderId, attempt, cause);
        if (orderId == null) {
            return;
        }
        // 异步重试，禁止在 confirm 回调（IO 线程）内阻塞或回滚订单
        retryExecutor.submit(() -> onSendFailure(orderId, attempt, "confirm nack: " + cause));
    }

    /** 失败裁决：未达上限则按 0.5/1/2s 退避重试；已达上限则落失败表停止，交给扫表兜底 */
    private void onSendFailure(Long orderId, int attempt, String reason) {
        if (attempt < MAX_RETRY) {
            int nextAttempt = attempt + 1;
            long backoff = RETRY_BACKOFF_MS[Math.min(attempt, RETRY_BACKOFF_MS.length - 1)];
            log.info("订单超时延迟消息准备第 {} 次重试，orderId={}，{}ms 后执行", nextAttempt, orderId, backoff);
            retryExecutor.schedule(() -> send(orderId, nextAttempt), backoff, TimeUnit.MILLISECONDS);
        } else {
            log.error("订单超时延迟消息重试 {} 次仍失败，orderId={}，停止重试，等待扫表兜底。reason={}",
                    MAX_RETRY, orderId, reason);
            recordFail(orderId, reason, attempt);
        }
    }

    /** 写入失败记录表；失败表自身写入异常不能再影响主流程，仅记日志 */
    private void recordFail(Long orderId, String reason, int retryCount) {
        try {
            MqSendFail fail = new MqSendFail();
            fail.setOrderId(orderId);
            fail.setReason(reason == null ? null
                    : reason.substring(0, Math.min(reason.length(), 512)));
            fail.setRetryCount(retryCount);
            fail.setCreateTime(LocalDateTime.now());
            mqSendFailMapper.insert(fail);
        } catch (Exception e) {
            log.error("写入 oms_mq_send_fail 失败，orderId={}", orderId, e);
        }
    }

    /** CorrelationData id 编码：orderId:attempt */
    private String correlationId(Long orderId, int attempt) {
        return orderId + ":" + attempt;
    }

    private Long parseOrderId(String cid) {
        if (cid == null) {
            return null;
        }
        try {
            return Long.valueOf(cid.split(":")[0]);
        } catch (NumberFormatException e) {
            log.warn("无法解析的 correlationId: {}", cid);
            return null;
        }
    }

    private int parseAttempt(String cid) {
        if (cid == null || !cid.contains(":")) {
            return 0;
        }
        try {
            return Integer.parseInt(cid.split(":")[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
