package com.xncoding.security.order;

import com.xncoding.security.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderStore store;
    private final AuditService audit;

    public OrderService(OrderStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    public Order create(String orderNo, BigDecimal amount) {
        Order order = store.create(orderNo, amount, currentUser());
        log.info("ORDER_EVENT created orderNo={} by={} count={}",
                order.orderNo(), order.createdBy(), store.count());
        return order;
    }

    public List<Order> list() {
        return store.all();
    }

    public Order get(String orderNo) {
        return store.get(orderNo);
    }

    /**
     * 取消订单是敏感操作：路径级规则放 OPERATOR 进来（/api/orders/**），
     * 收敛到 ADMIN 由方法级 @PreAuthorize 完成。即使调用方绕过 HTTP 直接注入本 bean，
     * 方法代理照样拦截。
     */
    @PreAuthorize("hasRole('ADMIN')")
    public Order cancel(String orderNo) {
        Order order = store.get(orderNo);
        if (Order.STATUS_CANCELLED.equals(order.status())) {
            return order;
        }
        Order cancelled = new Order(order.orderNo(), order.amount(), Order.STATUS_CANCELLED,
                order.createdBy(), order.createdAt());
        store.update(cancelled);
        audit.record(currentUser(), "取消订单", orderNo, "OK", "订单已取消");
        log.info("ORDER_EVENT cancelled orderNo={} by={}", orderNo, currentUser());
        return cancelled;
    }

    static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : auth.getName();
    }
}
