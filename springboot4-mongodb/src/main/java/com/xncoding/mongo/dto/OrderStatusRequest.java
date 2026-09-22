package com.xncoding.mongo.dto;

import com.xncoding.mongo.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 状态流转入参。
 *
 * @param status  目标状态
 * @param version 客户端的乐观锁版本号，可不传；不传就跳过并发校验
 */
public record OrderStatusRequest(

        @NotNull(message = "目标状态不能为空")
        OrderStatus status,

        Long version
) {
}
