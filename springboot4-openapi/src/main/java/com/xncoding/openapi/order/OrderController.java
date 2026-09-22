package com.xncoding.openapi.order;

import java.net.URI;
import java.security.Principal;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "订单", description = "订单查询与创建，面向前端与第三方联调")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @Operation(summary = "订单列表", description = "按状态过滤，不传 status 返回全部")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功",
                    useReturnTypeSchema = true)})
    @GetMapping
    public List<OrderResponse> list(
            @Parameter(description = "按订单状态过滤", in = ParameterIn.QUERY,
                    example = "NEW")
            @RequestParam(required = false) OrderStatus status) {
        return orders.list(status).stream().map(OrderResponse::from).toList();
    }

    @Operation(summary = "订单详情")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "订单不存在",
                    ref = "#/components/responses/NotFound")})
    @GetMapping("/{orderId}")
    public OrderResponse detail(@PathVariable Long orderId) {
        return orders.byId(orderId).map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Operation(summary = "创建订单", description = "业务单号全局唯一，重复单号返回 409")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "创建成功",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "请求体校验失败",
                    ref = "#/components/responses/BadRequest"),
            @ApiResponse(responseCode = "409", description = "单号重复",
                    ref = "#/components/responses/Duplicate")})
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req,
                                                Principal principal) {
        String createdBy = principal == null
                || principal instanceof AnonymousAuthenticationToken
                ? "system" : principal.getName();
        Order order = orders.create(req.orderNo(), req.amount(), req.note(), createdBy);
        return ResponseEntity
                .created(URI.create("/api/orders/" + order.id()))
                .body(OrderResponse.from(order));
    }
}
