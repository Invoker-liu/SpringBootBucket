package com.xncoding.mybatis.dto;

import com.xncoding.mybatis.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 状态流转入参。
 *
 * @param status  目标状态
 * @param version 可选。传了就走乐观锁，用于"读-改-写"场景下的并发保护：
 *                两个请求同时基于同一份快照提交，只有一个能成功，另一个拿到 409。
 */
public record OrderStatusRequest(

        @NotNull(message = "目标状态不能为空")
        OrderStatus status,

        @PositiveOrZero(message = "版本号不能为负数")
        Integer version
) {
}
