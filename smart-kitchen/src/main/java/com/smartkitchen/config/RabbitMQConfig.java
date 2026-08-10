package com.smartkitchen.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 订单超时延迟消息配置类
 * 使用死信队列（DLX）+ TTL 方案实现订单超时自动取消
 *
 * 正常流程：
 *   submitOrder 发送到延迟队列 → 30分钟后消息过期 → 死信交换机路由到超时消费队列 → 消费者处理
 *
 * 异常补偿流程：
 *   消费者处理异常 → basicNack(requeue=false) → 超时队列 DLX 转发 → 死信队列（DLQ）→ 人工处理
 */
@Configuration
public class RabbitMQConfig {

    /** 延迟交换机 */
    public static final String ORDER_DELAY_EXCHANGE = "order.delay.exchange";
    /** 延迟队列 */
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";
    /** 死信交换机 */
    public static final String ORDER_TIMEOUT_EXCHANGE = "order.timeout.exchange";
    /** 超时消费队列 */
    public static final String ORDER_TIMEOUT_QUEUE = "order.timeout.queue";
    /** 延迟路由键 */
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay";
    /** 超时路由键 */
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "order.timeout";
    /** 超时队列的死信交换机（异常消息转发至此） */
    public static final String ORDER_TIMEOUT_DLX = "order.timeout.dlx";
    /** 超时死信队列（异常消息暂存，待人工处理） */
    public static final String ORDER_TIMEOUT_DLQ = "order.timeout.dlq";
    /** 超时死信路由键 */
    public static final String ORDER_TIMEOUT_DLX_ROUTING_KEY = "order.timeout.dlq";

    /** 超时时长：30分钟（毫秒） */
    private static final int ORDER_TTL_MS = 30 * 60 * 1000;

    /**
     * 延迟交换机
     */
    @Bean
    public DirectExchange delayExchange() {
        return new DirectExchange(ORDER_DELAY_EXCHANGE, true, false);
    }

    /**
     * 延迟队列：设置 TTL 和死信交换机，消息在此队列中等待 30 分钟过期后转发到死信交换机
     */
    @Bean
    public Queue delayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", ORDER_TTL_MS);
        args.put("x-dead-letter-exchange", ORDER_TIMEOUT_EXCHANGE);
        args.put("x-dead-letter-routing-key", ORDER_TIMEOUT_ROUTING_KEY);
        return QueueBuilder.durable(ORDER_DELAY_QUEUE).withArguments(args).build();
    }

    /**
     * 死信交换机：接收延迟队列中过期的消息并重新路由
     */
    @Bean
    public DirectExchange timeoutExchange() {
        return new DirectExchange(ORDER_TIMEOUT_EXCHANGE, true, false);
    }

    /**
     * 超时消费队列：消费者实际监听此队列，收到消息后处理订单超时逻辑
     * 同时配置死信交换机，消费者 reject/nack(requeue=false) 的消息将进入 DLQ
     */
    @Bean
    public Queue timeoutQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", ORDER_TIMEOUT_DLX);
        args.put("x-dead-letter-routing-key", ORDER_TIMEOUT_DLX_ROUTING_KEY);
        return QueueBuilder.durable(ORDER_TIMEOUT_QUEUE).withArguments(args).build();
    }

    /**
     * 延迟绑定：延迟交换机 → 延迟队列
     */
    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue()).to(delayExchange()).with(ORDER_DELAY_ROUTING_KEY);
    }

    /**
     * 超时绑定：死信交换机 → 超时消费队列
     */
    @Bean
    public Binding timeoutBinding() {
        return BindingBuilder.bind(timeoutQueue()).to(timeoutExchange()).with(ORDER_TIMEOUT_ROUTING_KEY);
    }

    /**
     * 超时死信交换机：接收消费者 nack 的消息并转发到 DLQ
     */
    @Bean
    public DirectExchange timeoutDlxExchange() {
        return new DirectExchange(ORDER_TIMEOUT_DLX, true, false);
    }

    /**
     * 超时死信队列：存储处理失败的订单超时消息，待人工处理
     */
    @Bean
    public Queue timeoutDlq() {
        return QueueBuilder.durable(ORDER_TIMEOUT_DLQ).build();
    }

    /**
     * 死信绑定：超时死信交换机 → 死信队列
     */
    @Bean
    public Binding timeoutDlxBinding() {
        return BindingBuilder.bind(timeoutDlq()).to(timeoutDlxExchange()).with(ORDER_TIMEOUT_DLX_ROUTING_KEY);
    }
}
