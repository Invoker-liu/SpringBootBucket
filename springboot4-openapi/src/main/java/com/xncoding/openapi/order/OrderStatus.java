package com.xncoding.openapi.order;

/**
 * 订单状态机。枚举名会原样进入 schema 的 enum 数组，
 * description 来自 @Schema(description=...)。
 */
public enum OrderStatus {
    /** 已创建，待支付 */
    NEW,
    /** 已支付，待发货 */
    PAID,
    /** 已发货 */
    SHIPPED,
    /** 已取消 */
    CANCELLED
}
