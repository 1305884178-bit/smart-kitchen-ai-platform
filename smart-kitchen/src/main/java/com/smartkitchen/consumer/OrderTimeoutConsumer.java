package com.smartkitchen.consumer;

import com.rabbitmq.client.Channel;
import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.entity.Order;
import com.smartkitchen.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 订单支付超时消费者（先付后做：15 分钟未支付自动取消）
 *
 * 监听 order.timeout.queue（由 x-delayed-message 延迟交换机到期投递），手动 ACK：
 * - 订单不存在 / 已有 pay_time / 状态不是 ORDERED → basicAck 跳过，不取消；
 * - 仅 ORDERED 且 pay_time 为空 → cancelOrderForTimeout（取消原因 PAY_TIMEOUT，返还库存，
 *   父单可级联未支付子单，子单超时只取消自己）；
 * - 与支付并发：取消 SQL 影响 0 行视为支付胜出，方法正常返回 → ACK，不进 DLQ；
 * - 业务异常 → basicNack(deliveryTag, false, false) 进 DLQ 待人工补偿。
 */
@Component
public class OrderTimeoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutConsumer.class);

    @Autowired
    private OrderService orderService;

    /**
     * 消费订单支付超时消息，校验订单状态后执行自动取消
     * @param orderIdStr 订单ID字符串
     * @param channel RabbitMQ 通道，用于手动ACK
     * @param deliveryTag 消息投递标签
     */
    @RabbitListener(queues = "order.timeout.queue", ackMode = "MANUAL")
    public void handleOrderTimeout(String orderIdStr, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            Long orderId = Long.valueOf(orderIdStr);
            Order order = orderService.getById(orderId);

            if (order == null) {
                log.warn("订单超时处理：订单不存在，orderId={}", orderId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 仅「ORDERED 且未支付」执行超时取消；已支付（pay_time 非空）/ 非 ORDERED 一律跳过。
            // 超时永不取消 SERVED。
            if (order.getStatus() != null && order.getStatus() == OrderStatusEnum.ORDERED.getCode()
                    && order.getPayTime() == null) {
                log.info("订单支付超时自动取消，orderId={}", orderId);
                orderService.cancelOrderForTimeout(orderId);
            } else {
                log.info("订单超时跳过：已支付或状态非 ORDERED，orderId={}, status={}, payTime={}",
                        orderId, order.getStatus(), order.getPayTime());
            }

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("订单超时处理异常，orderIdStr={}，将投递到死信队列", orderIdStr, e);
            try {
                // basicNack(requeue=false)：拒绝消息且不重新入队，消息将进入超时队列的死信队列（DLQ）等待人工处理
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ex) {
                log.error("Nack失败", ex);
            }
        }
    }
}
