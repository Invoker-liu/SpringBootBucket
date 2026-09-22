package com.xncoding.echarts.stats;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 统计数据源：内存固定种子生成，保证每次启动、每次验证拿到的数字一致，
 * 文章正文与取值单同源。生产环境替换成 JdbcTemplate 聚合查询即可，接口形状不变。
 */
@Service
public class StatsService {

    /** 类目固定，金额与单量带固定种子抖动 */
    private static final String[] CATEGORIES = {"图书", "数码", "家居", "服饰", "食品"};

    /** 统计口径最多回看 30 天，防止一次请求拖出全表 */
    private static final int MAX_DAYS = 30;

    public List<DailyStat> daily(int days) {
        int n = clamp(days);
        LocalDate today = LocalDate.now();
        // 固定种子：同一天内重复请求得到完全一致的序列，导出的 PNG 因此可复现
        Random random = new Random(today.toEpochDay());
        List<DailyStat> items = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long orderCount = 40 + random.nextInt(80);
            BigDecimal amount = BigDecimal.valueOf(2000 + random.nextInt(6000) + random.nextInt(100) / 100.0)
                    .setScale(2, RoundingMode.HALF_UP);
            items.add(new DailyStat(date, orderCount, amount));
        }
        return items;
    }

    public List<CategoryStat> category() {
        LocalDate today = LocalDate.now();
        Random random = new Random(today.toEpochDay() * 31);
        List<CategoryStat> items = new ArrayList<>(CATEGORIES.length);
        for (String category : CATEGORIES) {
            long orderCount = 30 + random.nextInt(120);
            BigDecimal amount = BigDecimal.valueOf(3000 + random.nextInt(9000) + random.nextInt(100) / 100.0)
                    .setScale(2, RoundingMode.HALF_UP);
            items.add(new CategoryStat(category, orderCount, amount));
        }
        return items;
    }

    private int clamp(int days) {
        if (days <= 0) {
            return 7;
        }
        return Math.min(days, MAX_DAYS);
    }
}
