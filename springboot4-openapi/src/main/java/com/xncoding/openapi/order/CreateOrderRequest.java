package com.xncoding.openapi.order;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 建单请求 DTO。@Schema 的 description / example 与校验注解（maxLength、minimum 等）
 * 会同时出现在 /v3/api-docs 的 components.schemas.CreateOrderRequest 里。
 */
public record CreateOrderRequest(

        @Schema(description = "业务单号，全局唯一", example = "SO-2026-0001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 32)
        String orderNo,

        @Schema(description = "订单金额，单位元", example = "359.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 8, fraction = 2)
        BigDecimal amount,

        @Schema(description = "备注，可空", example = "加急发顺丰", maxLength = 200)
        @Size(max = 200)
        String note) {
}
