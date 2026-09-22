package com.xncoding.mybatis.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 订单明细里的一条商品。
 * <p>
 * 它不是表的一部分，整个列表会被序列化成 JSON 存进 {@code t_order.items} 这**一个**列里。
 * 下面的校验注解要在请求体进 Controller 时生效，所以入参 DTO 上得标 {@code @Valid} 让校验级联下来。
 * <p>
 * 这里刻意不写 {@code subtotal()} 这种"只读的计算属性"。存进 {@code items} 列的 JSON 还要被读回来，
 * 而 {@code Jackson3TypeHandler} 内部用的是它自己 {@code new} 出来的 {@code ObjectMapper}，
 * 不是 Spring 容器里那个（Boot 默认关掉了"未知字段报错"，裸实例是开着的）。
 * 往 JSON 里多塞一个字段，读取时就会直接失败。
 */
public record OrderItem(

        @NotBlank(message = "商品名称不能为空")
        @Size(max = 64, message = "商品名称长度不能超过 64")
        String name,

        @NotNull(message = "商品数量不能为空")
        @Positive(message = "商品数量必须大于 0")
        Integer quantity,

        @NotNull(message = "商品单价不能为空")
        @DecimalMin(value = "0.01", message = "商品单价必须大于 0")
        BigDecimal price
) {
}
