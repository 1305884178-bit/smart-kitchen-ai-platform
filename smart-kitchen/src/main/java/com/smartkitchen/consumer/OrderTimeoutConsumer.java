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
 * 订单超时消费者
 * 监听超时消费队列，处理 30 分钟未支付的自动取消逻辑
 */
@Component
public class OrderTimeoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutConsumer.class);

    @Autowired
    private OrderService orderService;

    /**
     * 消费订单超时消息，校验订单状态后执行自动取消
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

            Integer status = order.getStatus();
            // 仅 ORDERED / SERVED 状态执行自动取消
            if (status != null && (status == OrderStatusEnum.ORDERED.getCode()
                    || status == OrderStatusEnum.SERVED.getCode())) {
                log.info("订单超时自动取消，orderId={}, 当前状态={}", orderId, status);
                orderService.cancelOrder(orderId);
            } else {
                log.info("订单超时跳过：订单已支付或已撤销，orderId={}, 当前状态={}", orderId, status);
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
