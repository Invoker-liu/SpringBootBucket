package com.xncoding.amqp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 拓扑声明：交换机、队列、绑定全部用 Bean 声明，
 * 自动配置的 {@code RabbitAdmin} 会在连接建立后统一向 broker 声明（幂等，重复执行无副作用）。
 *
 * <p>拓扑设计（订单事件广播 + 死信兜底）：
 * <pre>
 *  RabbitTemplate.publish
 *        │ routing key = order.created / order.paid
 *        ▼
 *  sb4.order.exchange (topic)
 *        │ binding: order.*
 *        ▼
 *  sb4.order.notify  ──消费失败重试耗尽──▶ 死信路由 ──▶ sb4.order.dlx (direct)
 *   (x-dead-letter-exchange = sb4.order.dlx)              │ rk = order.dead
 *        │                                                ▼
 *   @RabbitListener 正常消费                        sb4.order.dead (死信队列)
 *                                                        │
 *                                                   @RabbitListener 记录兜底
 * </pre>
 *
 * <p>业务队列声明时挂上 {@code x-dead-letter-exchange} / {@code x-dead-letter-routing-key}
 * 两个参数：消息被 reject 且不 requeue 时，broker 自动把它转发到死信交换机——
 * 不需要业务代码"手动把失败消息写进另一个队列"。
 */
@Configuration
public class RabbitTopologyConfig {

    /** 订单业务交换机 */
    public static final String ORDER_EXCHANGE = "sb4.order.exchange";
    /** 通知队列（业务消费入口） */
    public static final String QUEUE_NOTIFY = "sb4.order.notify";
    /** 死信交换机 */
    public static final String DLX_EXCHANGE = "sb4.order.dlx";
    /** 死信队列 */
    public static final String QUEUE_DEAD = "sb4.order.dead";
    /** 订单创建事件的 routing key */
    public static final String RK_ORDER_CREATED = "order.created";
    /** 订单支付事件的 routing key */
    public static final String RK_ORDER_PAID = "order.paid";
    /** 死信的 routing key */
    public static final String RK_ORDER_DEAD = "order.dead";

    @Bean
    public TopicExchange orderExchange() {
        return ExchangeBuilder.topicExchange(ORDER_EXCHANGE).durable(true).build();
    }

    /**
     * 业务队列：挂死信参数。QueueBuilder 的 deadLetterExchange/deadLetterRoutingKey
     * 就是给队列附加 x-dead-letter-* 参数的便捷方法。
     */
    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFY)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(RK_ORDER_DEAD)
                .build();
    }

    @Bean
    public Binding notifyBinding() {
        // order.* 同时匹配 order.created 与 order.paid
        return BindingBuilder.bind(notifyQueue()).to(orderExchange()).with("order.*");
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QUEUE_DEAD).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(RK_ORDER_DEAD);
    }
}
