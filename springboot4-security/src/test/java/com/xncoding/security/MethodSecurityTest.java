package com.xncoding.security;

import com.xncoding.security.order.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 方法级安全的第二现场：绕过 HTTP 直接调用服务层 bean，
 * @PreAuthorize 的方法代理照样拦。这也是它和路径级规则的本质分工。
 */
@SpringBootTest
class MethodSecurityTest {

    @Autowired
    private OrderService orderService;

    @Test
    @WithMockUser(username = "op1", roles = "OPERATOR")
    void direct_service_call_is_stopped_for_operator() {
        assertThatThrownBy(() -> orderService.cancel("SK-DIRECT-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "rootadmin", roles = "ADMIN")
    void direct_service_call_passes_for_admin() {
        orderService.create("SK-DIRECT-2", java.math.BigDecimal.TEN);
        assertThatCode(() -> orderService.cancel("SK-DIRECT-2"))
                .doesNotThrowAnyException();
    }
}
