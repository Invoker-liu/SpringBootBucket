package com.xncoding.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GroupedOpenApi 分组行为：每组一份独立文档，互不越界；
 * swagger-config 是 swagger-ui 下拉框的数据源。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupedApiDocsTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void orders_group_only_contains_order_paths() throws Exception {
        mockMvc.perform(get("/v3/api-docs/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/orders']").exists())
                .andExpect(jsonPath("$.paths['/api/orders/{orderId}']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/orders']").doesNotExist());
    }

    @Test
    void admin_group_only_contains_admin_paths() throws Exception {
        mockMvc.perform(get("/v3/api-docs/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/admin/orders']").exists())
                .andExpect(jsonPath("$.paths['/api/admin/orders/{orderId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/orders']").doesNotExist());
    }

    @Test
    void swagger_config_lists_two_groups() throws Exception {
        // name 字段取的是 GroupedOpenApi 的 displayName，不是 group 名
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls[0].name").value("订单接口"))
                .andExpect(jsonPath("$.urls[0].url").value("/v3/api-docs/orders"))
                .andExpect(jsonPath("$.urls[1].name").value("订单运维接口"))
                .andExpect(jsonPath("$.urls[1].url").value("/v3/api-docs/admin"));
    }
}
