package com.xncoding.redis.controller;

import com.xncoding.redis.dto.VisitStatsResponse;
import com.xncoding.redis.service.VisitStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 访问统计接口：演示原子自增（INCR）与集合去重（SADD/SCARD）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final VisitStatsService visitStatsService;

    public StatsController(VisitStatsService visitStatsService) {
        this.visitStatsService = visitStatsService;
    }

    /**
     * 记录一次访问并返回统计。带 email 参数时参与当日去重访客计数。
     */
    @GetMapping("/visits")
    public VisitStatsResponse visits(@RequestParam(required = false) String email) {
        return visitStatsService.recordVisit(email);
    }

    /**
     * 只读统计：不产生访问记录。
     */
    @GetMapping("/visits/peek")
    public VisitStatsResponse peek() {
        return visitStatsService.peek();
    }
}
