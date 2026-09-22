package com.xncoding.jpa.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 订单明细入参。
 * <p>
 * 这是和上一篇一个不起眼但很实际的区别：上一篇的明细直接复用实体类
 * （{@code com.xncoding.mybatis.domain.OrderItem}）当入参，因为它只是个纯数据 POJO。
 * 这一篇不行——{@code OrderItem} 是 JPA 实体，身上有 {@code order} 这个反向引用，
 * 直接拿它接请求体会把关联对象也暴露给客户端，序列化时还会和订单互相引用打成死循环。
 * <p>
 * 一旦实体承担了持久化职责，就必须有独立的传输对象。这不是洁癖，是硬约束。
 */
public record OrderItemRequest(

        @NotBlank(message = "商品名称不能为空")
        @Size(max = 128, message = "商品名称长度不能超过 128")
        String productName,

        @NotNull(message = "单价不能为空")
        @DecimalMin(value = "0.01", message = "单价必须大于 0")
        @Digits(integer = 10, fraction = 2, message = "单价最多 10 位整数、2 位小数")
        BigDecimal price,

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量不能小于 1")
        @Max(value = 9999, message = "数量不能大于 9999")
        Integer quantity
) {
}
