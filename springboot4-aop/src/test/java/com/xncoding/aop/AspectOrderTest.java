package com.xncoding.aop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 切面顺序实测：@Order(1..4) 应该呈现「先进后出」的洋葱结构。
 * 一次创建订单请求的日志序列：
 * IDEM enter -> METRICS enter -> TRACE enter -> AUDIT enter ->
 * 业务 -> AUDIT done -> TRACE return/exit -> METRICS exit -> IDEM exit。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AspectOrderTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fourAspects_wrap_like_onion(CapturedOutput output) throws Exception {
        String orderNo = "SK-ORD-" + System.nanoTime();
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"%s","amount":"66.00","itemCount":1}
                                """.formatted(orderNo)))
                .andExpect(status().isCreated());

        List<String> marks = Arrays.stream(output.toString().split("\\r?\\n"))
                .filter(l -> l.contains("AOP_"))
                .map(l -> l.replaceAll(".*(AOP_[A-Z]+ (?:enter|done|exit|return|reject)).*", "$1"))
                .toList();

        assertThat(marks).containsSubsequence(
                "AOP_IDEM enter",
                "AOP_METRICS enter",
                "AOP_AUDIT enter",
                "AOP_TRACE enter",
                "AOP_TRACE return",
                "AOP_TRACE exit",
                "AOP_AUDIT done",
                "AOP_METRICS exit",
                "AOP_IDEM exit");
        // 先进后出：service 层跟踪先退出，审计再落库退出，指标与幂等最后收口
        assertThat(marks).containsSubsequence(
                "AOP_TRACE exit", "AOP_AUDIT done", "AOP_METRICS exit", "AOP_IDEM exit");
    }

    @Test
    void business_exception_still_unwinds_in_order(CapturedOutput output) throws Exception {
        String orderNo = "SK-ERR-" + System.nanoTime();
        // itemCount 超过初始库存，业务抛 InsufficientStockException
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"%s","amount":"1.00","itemCount":9999}
                                """.formatted(orderNo)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("库存不足"));

        List<String> marks = Arrays.stream(output.toString().split("\\r?\\n"))
                .filter(l -> l.contains("AOP_"))
                .map(l -> l.replaceAll(".*(AOP_[A-Z]+ (?:enter|done|exit|return|throws|reject)).*", "$1"))
                .toList();

        assertThat(marks).containsSubsequence(
                "AOP_IDEM enter",
                "AOP_METRICS enter",
                "AOP_AUDIT enter",
                "AOP_TRACE enter",
                "AOP_TRACE throws",
                "AOP_TRACE exit",
                "AOP_AUDIT done",
                "AOP_METRICS exit",
                "AOP_IDEM exit");
    }
}
