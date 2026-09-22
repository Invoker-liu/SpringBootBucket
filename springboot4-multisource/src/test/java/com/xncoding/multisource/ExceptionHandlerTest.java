package com.xncoding.multisource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证异常处理器在「异常被中间层重新包装」之后还能不能认出路由失败。
 *
 * <p>这个测试类是被 {@code DynamicRoutingTest} 里一条失败的断言带出来的。
 * 把 {@code @ExceptionHandler} 按原始类型挂上以后，
 * 请求实际上落到的是通用兜底分支 —— 状态码对了（都是 500），
 * 但响应体里那句最有用的
 * {@code Cannot determine target DataSource for lookup key [REPORT]} 不见了，
 * 换成了「服务端内部错误，请稍后重试」。
 *
 * <p>状态码一样、<b>语义差很多</b>。所以这里断言的是 {@code type} 和 {@code detail}，
 * 不只是状态码 —— 只断言 500 的话，这个 bug 会被测试放过去。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("路由失败穿过 MyBatis 的异常翻译之后，仍然返回带明细的 problem+json")
    void routingFailureIsStillRecognizedAfterWrapping() throws Exception {
        mockMvc.perform(get("/api/dynamic/probe/unregistered"))
                .andExpect(status().isInternalServerError())
                // 认出来了，不是走到通用兜底
                .andExpect(jsonPath("$.type").value("urn:problem-type:datasource-routing"))
                .andExpect(jsonPath("$.title").value("数据源路由失败"))
                // 原始提示留住了
                .andExpect(jsonPath("$.detail", containsString("REPORT")))
                // 并且能看到最里面那一层是什么
                .andExpect(jsonPath("$.cause").value("IllegalStateException"));
    }

    @Test
    @DisplayName("资源不存在仍然是干净的 404")
    void missingResourceReturns404() throws Exception {
        mockMvc.perform(get("/api/static/orders/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem-type:resource-not-found"))
                .andExpect(jsonPath("$.resourceType").value("订单"))
                .andExpect(jsonPath("$.resourceId").value(999999));
    }
}
