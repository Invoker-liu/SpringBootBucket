package com.xncoding.restful.domain;

/**
 * 订单状态。
 * <p>
 * 状态流转由业务层约束，客户端只能通过 {@code PATCH /api/orders/{id}/status} 触发流转，
 * 不允许在更新接口中随意改状态。
 */
public enum OrderStatus {

    /** 已创建，待支付 */
    CREATED("已创建"),

    /** 已支付，待发货 */
    PAID("已支付"),

    /** 已发货 */
    SHIPPED("已发货"),

    /** 已完成 */
    COMPLETED("已完成"),

    /** 已取消 */
    CANCELLED("已取消");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 判断当前状态是否可以流转到目标状态。
     *
     * @param target 目标状态
     * @return 允许流转返回 true
     */
    public boolean canTransferTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == PAID || target == CANCELLED;
            case PAID -> target == SHIPPED || target == CANCELLED;
            case SHIPPED -> target == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
