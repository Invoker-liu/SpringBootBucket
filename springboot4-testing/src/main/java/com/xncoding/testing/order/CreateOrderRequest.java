package com.xncoding.testing.order;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(

        @NotBlank
        @Size(max = 32)
        String orderNo,

        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal amount) {
}
