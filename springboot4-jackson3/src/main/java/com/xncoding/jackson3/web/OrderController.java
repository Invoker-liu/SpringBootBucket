package com.xncoding.jackson3.web;

import com.xncoding.jackson3.domain.Order;
import com.xncoding.jackson3.service.OrderService;
import com.xncoding.jackson3.web.dto.OrderAmountResponse;
import com.xncoding.jackson3.web.dto.OrderCreateRequest;
import com.xncoding.jackson3.web.dto.OrderFieldIncludeResponse;
import com.xncoding.jackson3.web.dto.OrderNonNullResponse;
import com.xncoding.jackson3.web.dto.OrderRecordResponse;
import com.xncoding.jackson3.web.dto.OrderResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    /** 默认行为：DTO 不贴任何注解，看 null 字段输出与否。 */
    @GetMapping("/{id}")
    public OrderResponse detail(@PathVariable Long id) {
        return OrderResponse.of(service.getById(id));
    }

    /** 类级 NON_NULL。 */
    @GetMapping("/{id}/non-null")
    public OrderNonNullResponse nonNull(@PathVariable Long id) {
        return OrderNonNullResponse.of(service.getById(id));
    }

    /** 字段级 NON_NULL：只作用于 couponCode。 */
    @GetMapping("/{id}/field-include")
    public OrderFieldIncludeResponse fieldInclude(@PathVariable Long id) {
        return OrderFieldIncludeResponse.of(service.getById(id));
    }

    /** record DTO 输出。 */
    @GetMapping("/{id}/record")
    public OrderRecordResponse record(@PathVariable Long id) {
        return OrderRecordResponse.of(service.getById(id));
    }

    /** 自定义序列化器：分转元。 */
    @GetMapping("/{id}/amount")
    public OrderAmountResponse amount(@PathVariable Long id) {
        return OrderAmountResponse.of(service.getById(id));
    }

    /** 反序列化容错综合端点：AS_EMPTY、primitive、元转分、别名。 */
    @PostMapping("/parse")
    public Map<String, Object> parse(@RequestBody OrderCreateRequest req) {
        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("customerName", req.getCustomerName());
        echo.put("merchantOrderNo", req.getMerchantOrderNo());
        echo.put("couponCode", req.getCouponCode());
        echo.put("quantity", req.getQuantity());
        echo.put("discountCents", req.getDiscountCents());
        echo.put("amountCents", req.getAmountCents());
        return echo;
    }

    /** 正常下单入口：请求体走同一套容错规则，落一条订单。 */
    @PostMapping
    public ResponseEntity<OrderNonNullResponse> create(@RequestBody OrderCreateRequest req) {
        Order order = service.create(req.getCustomerName(), req.getMerchantOrderNo(),
                req.getCouponCode(), req.getQuantity(), req.getAmountCents() == null
                        ? 0L : req.getAmountCents(),
                req.getDiscountCents() == null || req.getDiscountCents() == 0L
                        ? null : req.getDiscountCents());
        return ResponseEntity
                .created(URI.create("/api/orders/" + order.getId()))
                .body(OrderNonNullResponse.of(order));
    }
}
