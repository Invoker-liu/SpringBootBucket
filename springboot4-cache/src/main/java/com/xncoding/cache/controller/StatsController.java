package com.xncoding.cache.controller;

import com.xncoding.cache.dto.StatsResponse;
import com.xncoding.cache.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口：dbHits 是"缓存是否生效"的裁判。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final OrderService orderService;

    public StatsController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public StatsResponse stats() {
        return new StatsResponse(orderService.dbHits(), orderService.orderCount());
    }
}
