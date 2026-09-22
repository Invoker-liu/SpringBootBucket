package com.xncoding.restful.dto;

import com.xncoding.restful.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 订单状态流转请求体。
 *
 * @param status 目标状态
 */
public record OrderStatusRequest(

        @NotNull(message = "目标状态不能为空")
        OrderStatus status
) {
}
