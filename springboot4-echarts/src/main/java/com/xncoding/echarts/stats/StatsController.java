package com.xncoding.echarts.stats;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 图表数据接口：只返回纯数据，不返回 ECharts option。
 * 字段形状按「类目轴一维数组 + 数值数组」的取数方式设计，前端拿到后自行拼 option，
 * 数据与渲染解耦——同一份接口可以喂 ECharts，也可以喂任何其他图表库或导出程序。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 按天订单量与销售额：折线图/柱状图数据源 */
    @GetMapping("/daily")
    public Map<String, Object> daily(@RequestParam(defaultValue = "7") int days) {
        List<DailyStat> items = statsService.daily(days);
        return Map.of(
                "days", items.size(),
                "items", items
        );
    }

    /** 按类目汇总：饼图/横向柱状图数据源 */
    @GetMapping("/category")
    public Map<String, Object> category() {
        List<CategoryStat> items = statsService.category();
        return Map.of(
                "total", items.size(),
                "items", items
        );
    }
}
