package com.xncoding.orderapp;

import com.xncoding.starter.order.notify.DefaultOrderNotifyService;
import com.xncoding.starter.order.notify.OrderNotifyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NotifyEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.context.ApplicationContext ctx;

    @Test
    void notify_endpoint_returns_rendered_content() throws Exception {
        mockMvc.perform(post("/api/orders/SK-NOTIFY-1/notify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOTIFIED"))
                .andExpect(jsonPath("$.smsClient").value("FlakySmsClient"))
                .andExpect(jsonPath("$.content").value(
                        "[零售]{\"event\":\"ORDER_CREATED\",\"orderNo\":\"SK-NOTIFY-1\"}"));
    }

    @Test
    void starter_service_is_the_default_impl_with_customizer_prefix() {
        OrderNotifyService service = ctx.getBean(OrderNotifyService.class);
        assertThat(service).isInstanceOf(DefaultOrderNotifyService.class);
        // customizer 收口生效：前缀来自 NotifyCustomizerConfig，而非整体覆盖默认实现
        assertThat(((DefaultOrderNotifyService) service).getPrefix()).isEqualTo("[零售]");
    }

    @Test
    void starter_builtin_sms_client_backed_off_to_user_bean() {
        // 应用自带 flakySmsClient 后，starter 的 loggingSmsClient 退位
        assertThat(ctx.containsBean("loggingSmsClient")).isFalse();
        assertThat(ctx.containsBean("flakySmsClient")).isTrue();
    }
}
