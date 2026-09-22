package com.xncoding.oauth2.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * scope 到权限的映射发生在认证层：scope claim 的每个值变成一条
 * SCOPE_ 前缀的 authority，方法级注解按 authority 收敛敏感操作。
 * 机器调用方没有角色，hasRole 在这类工程里没有意义。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderStore store;

    public OrderService(OrderStore store) {
        this.store = store;
    }

    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    public java.util.List<Order> list() {
        return store.all();
    }

    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    public Order get(String orderNo) {
        return store.get(orderNo);
    }

    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    public Order create(String orderNo, BigDecimal amount) {
        Order order = store.create(orderNo, amount, currentUser());
        log.info("ORDER_EVENT created orderNo={} by={} count={}",
                order.orderNo(), order.createdBy(), store.all().size());
        return order;
    }

    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    public Order cancel(String orderNo) {
        Order order = store.get(orderNo);
        if (Order.STATUS_CANCELLED.equals(order.status())) {
            return order;
        }
        Order cancelled = new Order(order.orderNo(), order.amount(), Order.STATUS_CANCELLED,
                order.createdBy(), order.createdAt());
        store.update(cancelled);
        log.info("ORDER_EVENT cancelled orderNo={} by={}", orderNo, currentUser());
        return cancelled;
    }

    static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : auth.getName();
    }
}
