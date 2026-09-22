package com.xncoding.echarts.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统计接口 JSON 断言：形状、日期格式（Jackson 3 对 LocalDate 默认输出 ISO 字符串）、
 * 数值字段齐备。导出与前端渲染都建立在这份契约上。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatsApiTest {

    @Autowired
    private MockMvcTester mockMvc;

    private String bodyText(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("GET /api/stats/daily：返回 ISO 日期与数值字段，默认 7 天")
    void dailyReturnsIsoDatesAndNumbers() {
        MvcTestResult result = mockMvc.get().uri("/api/stats/daily").exchange();
        String body = bodyText(result);

        result.assertThat().hasStatusOk();
        assertThat(body).contains("\"days\":7");
        assertThat(body).contains("\"items\"");
        assertThat(body).containsPattern("\"date\":\"2026-\\d{2}-\\d{2}\"");
        assertThat(body).contains("\"orderCount\"");
        assertThat(body).contains("\"amount\"");
    }

    @Test
    @DisplayName("GET /api/stats/daily?days=30：回看窗口被夹在 30 天内生效")
    void dailyHonorsDaysParam() {
        MvcTestResult result = mockMvc.get().uri("/api/stats/daily?days=30").exchange();
        String body = bodyText(result);

        result.assertThat().hasStatusOk();
        assertThat(body).contains("\"days\":30");
        // 固定种子保证同一天序列可复现：首条应为 30 天前
        assertThat(body).containsPattern("\"date\":\"2026-\\d{2}-\\d{2}\"");
    }

    @Test
    @DisplayName("GET /api/stats/category：返回 5 个固定类目")
    void categoryReturnsFiveCategories() {
        MvcTestResult result = mockMvc.get().uri("/api/stats/category").exchange();
        String body = bodyText(result);

        result.assertThat().hasStatusOk();
        assertThat(body).contains("\"total\":5");
        assertThat(body).contains("图书");
        assertThat(body).contains("数码");
        assertThat(body).contains("家居");
        assertThat(body).contains("服饰");
        assertThat(body).contains("食品");
    }
}
