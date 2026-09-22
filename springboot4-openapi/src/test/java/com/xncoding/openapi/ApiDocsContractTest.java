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
 * /v3/api-docs JSON 的契约测试：openapi 版本、路径清单、schema 映射、
 * 复用响应组件与安全方案声明，全部断言打在文档本体上。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsContractTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void openapi_version_is_3_1() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.info.title").value("订单服务 API"));
    }

    @Test
    void paths_inventory_matches_controllers() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/orders']").exists())
                .andExpect(jsonPath("$.paths['/api/orders'].get.summary").value("订单列表"))
                .andExpect(jsonPath("$.paths['/api/orders'].post.operationId").value("create"))
                .andExpect(jsonPath("$.paths['/api/orders/{orderId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/admin/orders'].get").exists())
                .andExpect(jsonPath("$.paths['/api/admin/orders/{orderId}'].delete").exists());
    }

    @Test
    void dto_constraints_land_in_schema() throws Exception {
        // orderNo：NotBlank+Size(max=32) -> required 数组 + maxLength
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(
                        "$.components.schemas.CreateOrderRequest.properties.orderNo.maxLength").value(32))
                .andExpect(jsonPath(
                        "$.components.schemas.CreateOrderRequest.properties.orderNo.type").value("string"))
                .andExpect(jsonPath(
                        "$.components.schemas.CreateOrderRequest.properties.amount.minimum").value(0.01))
                .andExpect(jsonPath("$.components.schemas.CreateOrderRequest.required[0]").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.CreateOrderRequest.properties.orderNo.description")
                        .value("业务单号，全局唯一"));
    }

    @Test
    void enum_and_field_naming_in_response_schema() throws Exception {
        // 驼峰字段名原样进入 schema；枚举被内联进 status 属性，enum 数组里是四个状态名
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(
                        "$.components.schemas.OrderResponse.properties.status.enum[0]").value("NEW"))
                .andExpect(jsonPath(
                        "$.components.schemas.OrderResponse.properties.status.enum[3]").value("CANCELLED"))
                .andExpect(jsonPath(
                        "$.components.schemas.OrderResponse.properties.status.type").value("string"))
                .andExpect(jsonPath(
                        "$.components.schemas.OrderResponse.properties.orderNo.type").value("string"))
                .andExpect(jsonPath(
                        "$.components.schemas.OrderResponse.properties.createdAt.format").value("date-time"));
    }

    @Test
    void shared_problem_responses_are_declared() throws Exception {
        // 管理接口声明了 401/403，创建订单声明了 409，响应体复用 components 里的组件
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(
                        "$.paths['/api/admin/orders/{orderId}'].delete.responses['401'].$ref")
                        .value("#/components/responses/Unauthorized"))
                .andExpect(jsonPath(
                        "$.paths['/api/admin/orders/{orderId}'].delete.responses['403'].$ref")
                        .value("#/components/responses/Forbidden"))
                .andExpect(jsonPath(
                        "$.paths['/api/orders'].post.responses['409'].$ref")
                        .value("#/components/responses/Duplicate"));
    }

    @Test
    void basic_security_scheme_is_declared() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"))
                .andExpect(jsonPath("$.tags[?(@.name == '订单运维')]").exists());
    }
}
