package com.xncoding.mybatis.domain;

/**
 * 订单状态。
 * <p>
 * 落库时存的是枚举名（{@code CREATED}、{@code PAID} …），不是 {@code ordinal()}。
 * MyBatis 默认用 {@code EnumTypeHandler} 按 {@code name()} 存取，正好满足这个需求：
 * 直接查数据库你能读懂 {@code status} 列，而按序号存的话，将来在枚举中间插一个值，
 * 历史数据的含义就全乱了。
 */
public enum OrderStatus {

    CREATED("已创建"),
    PAID("已支付"),
    SHIPPED("已发货"),
    COMPLETED("已完成"),
    CANCELLED("已取消");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    /** 给前端展示用的中文名，只在响应体里出现，不入库 */
    public String getLabel() {
        return label;
    }

    /**
     * 状态机唯一的合法流转判定。
     * <p>
     * 用 switch 表达式穷举，将来新增状态时编译器会直接报错，不会漏掉分支。
     */
    public boolean canTransferTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == PAID || target == CANCELLED;
            case PAID -> target == SHIPPED || target == CANCELLED;
            case SHIPPED -> target == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    /** 终态：既不能再流转，也不允许修改业务字段 */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
