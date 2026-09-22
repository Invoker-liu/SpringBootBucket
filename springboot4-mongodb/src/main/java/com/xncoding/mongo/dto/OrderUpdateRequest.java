package com.xncoding.mongo.dto;

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
 * 整体更新入参。
 *
 * @param items   传 null 表示不动明细，传空数组表示清空明细
 * @param version 客户端的乐观锁版本号，可不传；不传就跳过并发校验
 */
public record OrderUpdateRequest(

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
        List<OrderItemRequest> items,

        Long version
) {
}
