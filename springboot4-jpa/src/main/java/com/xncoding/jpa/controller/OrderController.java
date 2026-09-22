package com.xncoding.jpa.controller;

import com.xncoding.jpa.domain.OrderPageQuery;
import com.xncoding.jpa.domain.OrderStatus;
import com.xncoding.jpa.dto.OrderCreateRequest;
import com.xncoding.jpa.dto.OrderResponse;
import com.xncoding.jpa.dto.OrderStatusRequest;
import com.xncoding.jpa.dto.OrderStatusStat;
import com.xncoding.jpa.dto.OrderUpdateRequest;
import com.xncoding.jpa.dto.PageResponse;
import com.xncoding.jpa.exception.BusinessException;
import com.xncoding.jpa.repository.OrderSummary;
import com.xncoding.jpa.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 订单接口。
 * <p>
 * 路径和状态码与上两篇保持一致，方便对照底层实现的差别。
 * <p>
 * <b>注意这里没有类级 {@code @Validated}</b>。加了它，方法参数校验会改由 AOP 代理处理，
 * 抛的是 {@code ConstraintViolationException} 而不是 {@code HandlerMethodValidationException}，
 * 全局异常处理器接不到，结果是 500 而不是 400。这是第一篇踩过的坑，后续都直接不写。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 创建订单，成功返回 201 与 Location 头。
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderCreateRequest request) {
        OrderResponse created = orderService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 分页查询。
     *
     * @param sort 排序表达式，形如 {@code createdAt,desc}。
     *             <b>用的是实体属性名，不是数据库列名</b>——写 {@code created_at} 会被
     *             {@link OrderPageQuery} 的白名单挡下来，回退到默认排序。
     */
    @GetMapping
    public PageResponse<OrderResponse> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "页码不能为负数") int page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = 100, message = "每页条数不能大于 100") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return orderService.page(buildQuery(keyword, status, page, size, sort));
    }

    /**
     * 解析 {@code sort=字段,方向} 表达式。
     * <p>
     * 只拆成两段：字段名交给 {@link OrderPageQuery} 的白名单去挡，方向在这里判死。
     * 方向写错是客户端笔误，应当明确报 422，而不是默默按默认方向排——
     * 后者会让调用方以为排序生效了，实际拿到的是另一套顺序。
     */
    private OrderPageQuery buildQuery(String keyword, OrderStatus status, int page, int size, String sort) {
        String sortBy = OrderPageQuery.DEFAULT_SORT_FIELD;
        boolean descending = true;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            sortBy = parts[0].trim();
            if (parts.length == 2) {
                String direction = parts[1].trim();
                if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
                    throw BusinessException.unprocessable("排序方向只支持 asc 或 desc，收到：" + direction);
                }
                descending = "desc".equalsIgnoreCase(direction);
            }
        }
        return new OrderPageQuery(keyword, status, page, size, sortBy, descending);
    }

    /**
     * 按状态聚合统计（JPQL 构造器表达式）。
     * <p>
     * 路径要写在 {@code /{id}} 之前。虽然 Spring MVC 的路径匹配本来就让字面量段
     * 优先于变量段（所以顺序其实不影响），但写在前面读起来更不容易误解。
     */
    @GetMapping("/stats")
    public List<OrderStatusStat> stats() {
        return orderService.stats();
    }

    /**
     * 订单摘要列表（接口投影，只查四列）。
     */
    @GetMapping("/summary")
    public List<OrderSummary> summary(@RequestParam @NotNull(message = "状态不能为空") OrderStatus status) {
        return orderService.summaries(status);
    }

    /**
     * 某状态下的有效订单数（原生 SQL）。
     */
    @GetMapping("/count")
    public Map<String, Object> count(@RequestParam @NotNull(message = "状态不能为空") OrderStatus status) {
        return Map.of("status", status, "count", orderService.countActive(status));
    }

    /**
     * 金额不低于指定值的订单（JPQL）。
     */
    @GetMapping("/expensive")
    public List<OrderResponse> expensive(
            @RequestParam(defaultValue = "100")
            @DecimalMin(value = "0.01", message = "金额必须大于 0") BigDecimal minAmount) {
        return orderService.notCheaperThan(minAmount);
    }

    /**
     * 按主键查询，带明细。
     */
    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return orderService.get(id);
    }

    /**
     * 整体更新。
     */
    @PutMapping("/{id}")
    public OrderResponse update(@PathVariable Long id, @Valid @RequestBody OrderUpdateRequest request) {
        return orderService.update(id, request);
    }

    /**
     * 状态流转。
     */
    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(@PathVariable Long id, @Valid @RequestBody OrderStatusRequest request) {
        return orderService.changeStatus(id, request);
    }

    /**
     * 删除订单。
     * <p>
     * 实体上有 {@code @SoftDelete}，所以这里是软删除：数据库里那条记录还在，
     * 只是 {@code deleted} 被置成了 1，之后所有 Hibernate 生成的查询都看不到它。
     * 但原生 SQL 不受影响——这也是为什么仓储里那句 {@code deleted = 0} 必须自己写。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        orderService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
