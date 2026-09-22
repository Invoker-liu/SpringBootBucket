package com.xncoding.jpa.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建订单入参。
 * <p>
 * 订单号、状态、版本号、时间戳都不在入参里：这些由服务端生成，客户端无权指定。
 *
 * @param items 订单明细，可为空。列表上必须标 {@code @Valid}，
 *              否则列表里每个元素自己带的校验注解不会被执行。
 */
public record OrderCreateRequest(

        @NotBlank(message = "客户姓名不能为空")
        @Size(max = 64, message = "客户姓名长度不能超过 64")
        String customerName,

        @NotBlank(message = "客户手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "客户手机号格式不正确")
        String customerPhone,

        @NotNull(message = "订单金额不能为空")
        @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
        @Digits(integer = 10, fraction = 2, message = "订单金额最多 10 位整数、2 位小数")
        BigDecimal totalAmount,

        @Size(max = 255, message = "备注长度不能超过 255")
        String remark,

        @Valid
        @Size(max = 20, message = "订单明细最多 20 条")
        List<OrderItemRequest> items
) {
}
