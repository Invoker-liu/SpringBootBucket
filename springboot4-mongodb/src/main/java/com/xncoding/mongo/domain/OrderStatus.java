package com.xncoding.mongo.domain;

/**
 * 订单状态。
 * <p>
 * <b>和上一篇（JPA）有一处关键差别：这里不需要 {@code @Enumerated(EnumType.STRING)}。</b>
 * JPA 的默认行为是存 {@code ordinal()}（也就是 0、1、2），必须显式声明才存枚举名；
 * 而 Spring Data MongoDB 没有"序号"这个概念，枚举一律按 {@code name()} 存成字符串。
 * <p>
 * 效果是一样的：文档里能直接读懂，将来在枚举中间插值也不会让历史数据整体错位。
 * 但"为什么不用写这个注解"是两套映射机制导致的，值得分清楚——
 * 在 MongoDB 里想存序号反而要自己写转换器。
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

    /** 给前端展示用的中文名，只在响应体里出现，不落库 */
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
