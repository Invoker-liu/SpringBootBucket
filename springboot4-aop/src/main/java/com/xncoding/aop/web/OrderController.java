package com.xncoding.aop.web;

import com.xncoding.aop.annotation.Idempotent;
import com.xncoding.aop.annotation.OperationLog;
import com.xncoding.aop.service.OrderService;
import com.xncoding.aop.store.OrderStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订单接口。create 与 pay 标了 @Idempotent（切面打回重复提交），
 * 四个写读方法都标了 @OperationLog（审计切面落库）。
 * 控制器本身不含任何切面逻辑，代理由容器自动织入。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public record CreateOrderRequest(String orderNo, String amount, int itemCount) {
    }

    public record OrderView(String orderNo, String amount, int itemCount,
                            String status, long placedAtMs) {

        static OrderView of(OrderStore.Order order) {
            return new OrderView(order.orderNo(), order.amount().toPlainString(),
                    order.itemCount(), order.status(), order.placedAtMs());
        }
    }

    @PostMapping
    @OperationLog(module = "订单", action = "创建订单")
    @Idempotent(windowSeconds = 60)
    public ResponseEntity<OrderView> create(@RequestBody CreateOrderRequest req) {
        OrderStore.Order order = orderService.createOrder(
                req.orderNo(), new java.math.BigDecimal(req.amount()), req.itemCount());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderView.of(order));
    }

    @GetMapping("/{orderNo}")
    @OperationLog(module = "订单", action = "查询订单")
    public OrderView detail(@PathVariable String orderNo) {
        return OrderView.of(orderService.detail(orderNo));
    }

    @PostMapping("/{orderNo}/pay")
    @OperationLog(module = "订单", action = "支付订单")
    @Idempotent(windowSeconds = 60)
    public OrderView pay(@PathVariable String orderNo) {
        return OrderView.of(orderService.pay(orderNo));
    }

    @PostMapping("/{orderNo}/pack")
    @OperationLog(module = "订单", action = "打包出库")
    public Map<String, Object> pack(@PathVariable String orderNo) throws InterruptedException {
        return orderService.pack(orderNo);
    }
}
