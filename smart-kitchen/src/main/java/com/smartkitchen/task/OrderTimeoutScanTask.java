package com.smartkitchen.task;

import com.smartkitchen.mapper.OrderMapper;
import com.smartkitchen.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 未支付超时订单扫表兜底任务
 *
 * 定位：MQ 链路的最终兜底——RabbitMQ 宕机、延迟插件未启用、confirm nack 重试 3 次仍失败、
 * 进程在 afterCommit 前崩溃等场景下，未支付单仍会在约 [15, 15+扫描间隔] 分钟内被关单并还库存。
 *
 * 扫描口径与 MQ 消费者一致：status=ORDERED 且 pay_time IS NULL 且 create_time < now - 支付窗口，
 * 父单子单都会命中，每次 LIMIT 100；取消走与消费端相同的条件更新（cancelIfUnpaid），重复扫描安全。
 *
 * 默认每 2 分钟一次（smart-kitchen.order.timeout-scan-ms，可配）；
 * 测试 profile 通过 smart-kitchen.order.timeout-scan-enabled=false 关闭本 Bean。
 */
@Component
@ConditionalOnProperty(prefix = "smart-kitchen.order", name = "timeout-scan-enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeoutScanTask {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScanTask.class);

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderService orderService;

    /** 支付窗口毫秒数，与 MQ 消息 x-delay 保持一致（默认 15 分钟） */
    @Value("${smart-kitchen.order.pay-timeout-ms:900000}")
    private long payTimeoutMs;

    @Scheduled(fixedDelayString = "${smart-kitchen.order.timeout-scan-ms:120000}",
            initialDelayString = "${smart-kitchen.order.timeout-scan-ms:120000}")
    public void scanTimeoutUnpaidOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusNanos(payTimeoutMs * 1_000_000L);
        List<Long> orderIds = orderMapper.selectTimeoutUnpaidOrderIds(deadline);
        if (orderIds.isEmpty()) {
            return;
        }
        log.info("扫表兜底：发现 {} 笔超时未支付订单，开始取消: {}", orderIds.size(), orderIds);
        for (Long orderId : orderIds) {
            try {
                // 与 MQ 消费端同一套取消逻辑：条件更新，重复执行/与支付并发均安全
                orderService.cancelOrderForTimeout(orderId);
            } catch (Exception e) {
                // 单笔失败不中断本轮扫描，等下一轮重试
                log.error("扫表兜底取消订单失败，orderId={}", orderId, e);
            }
        }
    }
}
