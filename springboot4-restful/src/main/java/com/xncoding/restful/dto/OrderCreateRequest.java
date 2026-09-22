package com.xncoding.restful.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 创建订单请求体。
 * <p>
 * 入参用独立 DTO 而非直接绑定领域模型：客户端能提交的字段被白名单化，
 * 也就无法通过"多传一个 status"绕过状态机。
 *
 * @param customerName  客户姓名
 * @param customerPhone 客户手机号
 * @param totalAmount   订单金额
 * @param remark        备注
 */
public record OrderCreateRequest(

        @NotBlank(message = "客户姓名不能为空")
        @Size(max = 32, message = "客户姓名长度不能超过 32")
        String customerName,

        @NotBlank(message = "客户手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "客户手机号格式不正确")
        String customerPhone,

        @NotNull(message = "订单金额不能为空")
        @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
        @Digits(integer = 10, fraction = 2, message = "订单金额最多 10 位整数、2 位小数")
        BigDecimal totalAmount,

        @Size(max = 200, message = "备注长度不能超过 200")
        String remark
) {
}
