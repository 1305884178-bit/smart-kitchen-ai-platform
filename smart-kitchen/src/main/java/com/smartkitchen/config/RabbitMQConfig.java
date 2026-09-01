package com.smartkitchen.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 订单支付超时延迟消息配置类
 *
 * 延迟方案：rabbitmq_delayed_message_exchange 插件（x-delayed-message 交换机），
 * 消息在延迟交换机内部等待 x-delay（消息头，毫秒）到期后，按 routing key 投递到已绑定队列。
 * 不使用「TTL 队列 + 死信转发」充当到期语义。
 *
 * 拓扑：
 *   生产者（事务提交后 afterCommit 发送，body=orderId，header x-delay=15min）
 *     → order.delay.exchange（type=x-delayed-message，durable，x-delayed-type=direct）
 *          绑定 rk=order.timeout
 *     → order.timeout.queue（普通 durable 队列，OrderTimeoutConsumer 手动 ACK 监听这里）
 *          仅消费失败时 basicNack(requeue=false) 经 DLX 转发
 *     → order.timeout.dlx → order.timeout.dlq（人工补偿）
 *
 * 本地/Docker 需启用插件：rabbitmq-plugins enable rabbitmq_delayed_message_exchange
 * 测试环境 listener auto-startup=false，单测 mock RabbitTemplate，不依赖真实插件。
 */
@Configuration
public class RabbitMQConfig {

    /** 延迟交换机（x-delayed-message 插件） */
    public static final String ORDER_DELAY_EXCHANGE = "order.delay.exchange";
    /** 超时消费队列 */
    public static final String ORDER_TIMEOUT_QUEUE = "order.timeout.queue";
    /** 延迟消息路由键（延迟交换机 → 超时消费队列） */
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "order.timeout";
    /** 超时队列的死信交换机（仅承载消费者 nack 的异常消息，不承担到期语义） */
    public static final String ORDER_TIMEOUT_DLX = "order.timeout.dlx";
    /** 超时死信队列（异常消息暂存，待人工处理） */
    public static final String ORDER_TIMEOUT_DLQ = "order.timeout.dlq";
    /** 超时死信路由键 */
    public static final String ORDER_TIMEOUT_DLX_ROUTING_KEY = "order.timeout.dlq";

    /**
     * 延迟交换机：x-delayed-message 类型，消息按 x-delay 头延迟后再路由
     */
    @Bean
    public CustomExchange delayExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(ORDER_DELAY_EXCHANGE, "x-delayed-message", true, false, args);
    }

    /**
     * 超时消费队列：消费者实际监听此队列，收到消息后处理订单超时逻辑
     * 配置死信交换机：消费者 reject/nack(requeue=false) 的消息将进入 DLQ
     */
    @Bean
    public Queue timeoutQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", ORDER_TIMEOUT_DLX);
        args.put("x-dead-letter-routing-key", ORDER_TIMEOUT_DLX_ROUTING_KEY);
        return QueueBuilder.durable(ORDER_TIMEOUT_QUEUE).withArguments(args).build();
    }

    /**
     * 延迟绑定：延迟交换机 → 超时消费队列（到期消息按此路由键投递）
     */
    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(timeoutQueue()).to(delayExchange()).with(ORDER_TIMEOUT_ROUTING_KEY).noargs();
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
