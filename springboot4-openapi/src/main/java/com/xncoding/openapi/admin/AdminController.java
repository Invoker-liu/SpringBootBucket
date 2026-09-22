package com.xncoding.openapi.admin;

import java.util.List;

import com.xncoding.openapi.order.OrderNotFoundException;
import com.xncoding.openapi.order.OrderResponse;
import com.xncoding.openapi.order.OrderService;
import com.xncoding.openapi.order.OrderStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "订单运维", description = "管理侧接口，HTTP Basic 认证，运营后台调用")
@SecurityRequirement(name = "basicAuth")
public class AdminController {

    private final OrderService orders;

    public AdminController(OrderService orders) {
        this.orders = orders;
    }

    @Operation(summary = "全量订单列表", description = "管理侧视图，不分页，可按状态过滤")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "401", description = "未认证",
                    ref = "#/components/responses/Unauthorized"),
            @ApiResponse(responseCode = "403", description = "权限不足",
                    ref = "#/components/responses/Forbidden")})
    @GetMapping("/orders")
    public List<OrderResponse> all(@RequestParam(required = false) OrderStatus status) {
        return orders.list(status).stream().map(OrderResponse::from).toList();
    }

    @Operation(summary = "删除订单", description = "物理删除，删除成功返回 204")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    ref = "#/components/responses/Unauthorized"),
            @ApiResponse(responseCode = "403", description = "权限不足",
                    ref = "#/components/responses/Forbidden"),
            @ApiResponse(responseCode = "404", description = "订单不存在",
                    ref = "#/components/responses/NotFound")})
    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<Void> delete(@PathVariable Long orderId) {
        if (!orders.delete(orderId)) {
            throw new OrderNotFoundException(orderId);
        }
        return ResponseEntity.noContent().build();
    }
}
