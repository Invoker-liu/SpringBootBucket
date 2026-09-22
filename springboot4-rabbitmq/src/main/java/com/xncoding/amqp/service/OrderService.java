package com.xncoding.amqp.service;

import com.xncoding.amqp.config.RabbitTopologyConfig;
import com.xncoding.amqp.domain.OrderEvent;
import com.xncoding.amqp.dto.OrderView;
import com.xncoding.amqp.exception.BusinessException;
import com.xncoding.amqp.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 订单服务：下单/支付 + 发布事件。
 *
 * <p>刻意保持"订单数据在内存"的最小实现——本篇的主题是消息链路，
 * 真实工程里订单在数据库里，发布事件通常与事务提交协同（见事务篇）。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final RabbitTemplate rabbitTemplate;
    private final NotificationService notificationService;

    /** 模拟订单库 */
    private final Map<String, OrderView> orders = new ConcurrentHashMap<>();
    /** 订单号序列 */
    private final AtomicLong orderSeq = new AtomicLong(1000);

    public OrderService(RabbitTemplate rabbitTemplate, NotificationService notificationService) {
        this.rabbitTemplate = rabbitTemplate;
        this.notificationService = notificationService;
    }

    public OrderView create(String product, String receiver) {
        String orderNo = "SO" + orderSeq.incrementAndGet();
        OrderView order = new OrderView(orderNo, product, receiver, "CREATED");
        orders.put(orderNo, order);
        publish(OrderEvent.TYPE_ORDER_CREATED, order, RabbitTopologyConfig.RK_ORDER_CREATED);
        return order;
    }

    public OrderView pay(String orderNo) {
        OrderView order = find(orderNo);
        if (!"CREATED".equals(order.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "订单 " + orderNo + " 当前状态 "
                    + order.status() + "，不能支付");
        }
        OrderView paid = new OrderView(order.orderNo(), order.product(), order.receiver(), "PAID");
        orders.put(orderNo, paid);
        publish(OrderEvent.TYPE_ORDER_PAID, paid, RabbitTopologyConfig.RK_ORDER_PAID);
        return paid;
    }

    public OrderView find(String orderNo) {
        OrderView order = orders.get(orderNo);
        if (order == null) {
            throw new ResourceNotFoundException("order", orderNo, "订单不存在: " + orderNo);
        }
        return order;
    }

    /** 发布毒丸事件：观察重试与死信的演示入口 */
    public OrderView publishPoison(String orderNo) {
        OrderView order = find(orderNo);
        publish(OrderEvent.TYPE_POISON, order, RabbitTopologyConfig.RK_ORDER_CREATED);
        return order;
    }

    private void publish(String type, OrderView order, String routingKey) {
        OrderEvent event = new OrderEvent(UUID.randomUUID().toString(), order.orderNo(),
                type, order.product(), Instant.now());
        notificationService.onPublished(event);
        rabbitTemplate.convertAndSend(RabbitTopologyConfig.ORDER_EXCHANGE, routingKey, event);
        log.info("[订单服务] 事件已发布: type={}, orderNo={}", type, order.orderNo());
    }

    /** 测试辅助：清空模拟库与计数器 */
    public void reset() {
        orders.clear();
        orderSeq.set(1000);
    }

    public List<OrderView> allOrders() {
        return List.copyOf(orders.values());
    }
}
