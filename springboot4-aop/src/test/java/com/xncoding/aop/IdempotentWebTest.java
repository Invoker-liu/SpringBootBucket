package com.xncoding.aop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等切面的 HTTP 行为：第一次放行，第二次 409（RFC 9457 problem+json）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotentWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createOrder_secondIdenticalRequest_is409() throws Exception {
        String orderNo = "SK-IDEM-" + System.nanoTime();
        String body = """
                {"orderNo":"%s","amount":"12.50","itemCount":1}
                """.formatted(orderNo);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("重复请求"))
                .andExpect(jsonPath("$.windowSeconds").value(60));
    }

    @Test
    void pay_secondRequestWithinWindow_is409() throws Exception {
        String orderNo = "SK-PAY-" + System.nanoTime();
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"%s","amount":"88.00","itemCount":2}
                                """.formatted(orderNo)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders/{orderNo}/pay", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(post("/api/orders/{orderNo}/pay", orderNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:duplicate-request"));
    }
}
