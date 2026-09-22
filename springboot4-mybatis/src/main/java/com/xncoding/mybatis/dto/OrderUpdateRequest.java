package com.xncoding.mybatis.dto;

import com.xncoding.mybatis.domain.OrderItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 整体更新订单入参。
 * <p>
 * PUT 语义是整体替换，所以四个业务字段都必填。订单号与状态不在其中：
 * 订单号不可改，状态只能走 {@code PATCH /api/orders/{id}/status} 那条路径。
 *
 * @param items   订单明细，整体替换；传空数组表示把明细清空
 * @param version 可选。传了就走乐观锁：与库中版本号不一致时更新影响 0 行，接口返回 409。
 *                不传则退化为普通更新。
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

        /** 允许为空字符串，表示把备注清空 */
        @Size(max = 255, message = "备注长度不能超过 255")
        String remark,

        @Valid
        @Size(max = 20, message = "订单明细最多 20 条")
        List<OrderItem> items,

        @PositiveOrZero(message = "版本号不能为负数")
        Integer version
) {
}
