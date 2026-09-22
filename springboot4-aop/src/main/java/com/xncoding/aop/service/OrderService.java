package com.xncoding.aop.service;

import com.xncoding.aop.exception.InsufficientStockException;
import com.xncoding.aop.exception.OrderNotFoundException;
import com.xncoding.aop.store.OrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 订单业务。方法体里没有任何切面相关的代码：
 * 计时、跟踪、审计、幂等都由外层切面完成，这就是横切关注点分离后的样子。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 打包出库模拟耗时，让 @Around 的计时统计有可辨认的数字 */
    static final long PACK_WORK_MS = 120;

    private final OrderStore store;

    public OrderService(OrderStore store) {
        this.store = store;
    }

    public OrderStore.Order createOrder(String orderNo, BigDecimal amount, int itemCount) {
        if (!store.tryDeduct(itemCount)) {
            throw new InsufficientStockException(itemCount, store.stockLeft());
        }
        OrderStore.Order order = new OrderStore.Order(orderNo, amount, itemCount,
                "CREATED", System.currentTimeMillis());
        store.save(order);
        log.info("ORDER_PLACED orderNo={} itemCount={} stockLeft={}",
                orderNo, itemCount, store.stockLeft());
        return order;
    }

    public OrderStore.Order detail(String orderNo) {
        return store.find(orderNo)
                .orElseThrow(() -> new OrderNotFoundException(orderNo));
    }

    public OrderStore.Order pay(String orderNo) {
        OrderStore.Order order = detail(orderNo);
        if ("PAID".equals(order.status())) {
            throw new IllegalStateException("订单已支付：" + orderNo);
        }
        OrderStore.Order paid = store.markPaid(order);
        log.info("ORDER_PAID orderNo={}", orderNo);
        return paid;
    }

    public Map<String, Object> pack(String orderNo) throws InterruptedException {
        OrderStore.Order order = detail(orderNo);
        // 模拟打包出库的耗时工作
        Thread.sleep(PACK_WORK_MS);
        return Map.of(
                "orderNo", order.orderNo(),
                "status", "PACKED",
                "workMs", PACK_WORK_MS);
    }
}
