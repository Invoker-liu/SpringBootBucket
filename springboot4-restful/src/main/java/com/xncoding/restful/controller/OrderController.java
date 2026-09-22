package com.xncoding.restful.controller;

import com.xncoding.restful.domain.Order;
import com.xncoding.restful.domain.OrderPageQuery;
import com.xncoding.restful.domain.OrderStatus;
import com.xncoding.restful.domain.PageSlice;
import com.xncoding.restful.dto.OrderCreateRequest;
import com.xncoding.restful.dto.OrderResponse;
import com.xncoding.restful.dto.OrderStatusRequest;
import com.xncoding.restful.dto.OrderUpdateRequest;
import com.xncoding.restful.dto.PageResponse;
import com.xncoding.restful.exception.BusinessException;
import com.xncoding.restful.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

import java.net.URI;

/**
 * 订单 RESTful 接口。
 * <p>
 * 资源路径统一用复数名词 {@code /api/orders}，动作语义交给 HTTP 方法表达；
 * 状态码严格按语义返回：创建 201 + Location、删除 204、查询与更新 200。
 * <p>
 * <b>注意这里没有 {@code @Validated}</b>：Spring Framework 6.1 起，控制器方法参数的约束校验
 * 已由 Spring MVC 内置完成，校验失败抛 {@code HandlerMethodValidationException}。
 * 一旦在类上标注 {@code @Validated}，就会退回旧的 AOP 代理校验链路，
 * 抛出的是 {@code ConstraintViolationException}，全局异常处理器按 400 处理的那条分支也就失效了。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 创建订单。
     * <p>
     * 返回 201 并在 Location 头中给出新资源地址，客户端无需自行拼接 URL。
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderCreateRequest request) {
        Order created = orderService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(OrderResponse.from(created));
    }

    /**
     * 按主键查询订单。
     */
    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable Long id) {
        return OrderResponse.from(orderService.getById(id));
    }

    /**
     * 分页 + 条件查询订单列表。
     * <p>
     * 查询条件通过查询串表达，不占用路径层级；排序字段走白名单，
     * 客户端无法借排序参数探测内部结构。
     *
     * @param keyword 订单号或客户姓名关键字
     * @param status  状态精确过滤
     * @param page    页码，从 0 开始
     * @param size    每页条数，1~100
     * @param sort    排序表达式，形如 {@code createdAt,desc}
     */
    @GetMapping
    public PageResponse<OrderResponse> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "页码不能小于 0") int page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = 100, message = "每页条数不能大于 100") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        OrderPageQuery query = buildQuery(keyword, status, page, size, sort);
        PageSlice<Order> slice = orderService.page(query);
        return PageResponse.of(slice.content(), query.page(), query.size(), slice.total(),
                OrderResponse::from);
    }

    /**
     * 整体更新订单。PUT 具备幂等性，同一请求重复提交结果一致。
     */
    @PutMapping("/{id}")
    public OrderResponse update(@PathVariable Long id,
                                @Valid @RequestBody OrderUpdateRequest request) {
        return OrderResponse.from(orderService.update(id, request));
    }

    /**
     * 状态流转。
     * <p>
     * 状态是订单的一个子资源维度，用 PATCH 表达"局部修改"，
     * 与 PUT 的整体替换语义区分开。
     */
    @PatchMapping("/{id}/status")
    public OrderResponse transferStatus(@PathVariable Long id,
                                       @Valid @RequestBody OrderStatusRequest request) {
        return OrderResponse.from(orderService.transferStatus(id, request.status()));
    }

    /**
     * 删除订单。重复删除返回 404 问题详情，与查询接口的错误响应格式保持一致。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        orderService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 解析 {@code sort=字段,方向} 表达式。
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
}
