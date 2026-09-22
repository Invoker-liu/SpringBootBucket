package com.xncoding.aimcp.order;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单域 MCP 工具层：注解驱动的工具暴露。
 * name 用蛇形命名（LLM 调用时的函数名），description 写清「什么时候该用我」，
 * 这两处都是给模型看的接口文档。
 */
@Service
public class OrderMcpTools {

    private final OrderService orderService;

    public OrderMcpTools(OrderService orderService) {
        this.orderService = orderService;
    }

    @McpTool(
            name = "get_order_by_id",
            description = "按订单号查询单笔订单详情，返回订单ID、客户ID、商品、金额、状态与下单时间。用户给出具体订单号（如 ORD-1001）时使用。"
    )
    public Order getOrderById(
            @McpToolParam(description = "订单号，格式 ORD-数字，例如 ORD-1001", required = true)
            String orderId
    ) {
        return orderService.getOrderById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));
    }

    @McpTool(
            name = "list_orders_by_customer",
            description = "按客户ID查询该客户的全部订单列表，按下单时间倒序返回。想看某个客户买过什么、下过几单时使用。"
    )
    public List<Order> listOrdersByCustomer(
            @McpToolParam(description = "客户ID，格式 C-数字，例如 C-001", required = true)
            String customerId
    ) {
        return orderService.listOrdersByCustomer(customerId);
    }

    @McpTool(
            name = "create_order",
            description = "为新客户或老客户创建一笔新订单，需要客户ID、商品名与金额三项，创建成功返回完整订单（状态为 CREATED）。"
    )
    public Order createOrder(
            @McpToolParam(description = "客户ID，例如 C-001", required = true)
            String customerId,
            @McpToolParam(description = "商品名称，例如 无线耳机", required = true)
            String product,
            @McpToolParam(description = "订单金额（元），例如 199.00", required = true)
            BigDecimal amount
    ) {
        return orderService.createOrder(customerId, product, amount);
    }

    /**
     * 组合工具：不做单条数据，而是把「筛选近 7 天 + 求和 + 总单数」
     * 三步业务逻辑包成一个工具，省掉 LLM 多轮调用。
     */
    @McpTool(
            name = "weekly_order_summary",
            description = "查询近 7 天订单汇总：近 7 天订单数、近 7 天订单总金额、历史订单总数。用户问最近一周订单情况、销售概况时使用，无需任何参数。"
    )
    public OrderService.WeeklySummary weeklyOrderSummary() {
        return orderService.weeklySummary();
    }
}
