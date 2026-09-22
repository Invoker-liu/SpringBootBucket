package com.xncoding.nativeapp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三个查询接口的端到端测试：真实启动 Tomcat（随机端口）+ MockMvc 请求断言，
 * 其中 /api/orders/stats 隐式验证 @ConfigurationProperties 绑定是否正确。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext context;

    @Test
    @DisplayName("按 id 查询订单返回明细")
    void detail() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/orders/order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer").value("alice"))
                .andExpect(jsonPath("$.item").value("机械键盘"))
                .andExpect(jsonPath("$.amount").value(699.00));
    }

    @Test
    @DisplayName("查询不存在的订单返回 404")
    void detailNotFound() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/orders/nope")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("列表接口返回全部三条种子订单")
    void list() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("统计接口返回配置绑定结果，yml 值生效")
    void stats() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/orders/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("shop=xncoding-shop, vipDiscount=0.88, bulkThreshold=9999, bulkOrders=1"));
    }
}
