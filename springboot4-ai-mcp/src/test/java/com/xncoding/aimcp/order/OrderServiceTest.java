package com.xncoding.aimcp.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具背后业务逻辑测试：直接调用服务层，验证创建、查询、汇总的真实取值。
 * 服务是内存单例，创建类用例会写状态，每个方法跑完重建上下文，避免用例间污染。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Test
    @DisplayName("创建订单后可按ID查到")
    void createThenGet() {
        Order created = orderService.createOrder("C-100", "无线耳机", new BigDecimal("199.00"));

        assertThat(created.id()).startsWith("ORD-");
        assertThat(created.status()).isEqualTo("CREATED");

        Order loaded = orderService.getOrderById(created.id()).orElseThrow();
        assertThat(loaded.product()).isEqualTo("无线耳机");
        assertThat(loaded.amount()).isEqualByComparingTo("199.00");
    }

    @Test
    @DisplayName("种子数据：C-001 有两笔近期订单且倒序")
    void listByCustomer() {
        List<Order> orders = orderService.listOrdersByCustomer("C-001");

        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).createdAt()).isAfter(orders.get(1).createdAt());
    }

    @Test
    @DisplayName("近7天汇总：种子数据下近7天应为2笔")
    void weeklySummary() {
        OrderService.WeeklySummary summary = orderService.weeklySummary();

        // 种子：3 笔订单中 2 笔在近 7 天内，1 笔（10 天前）在窗口外
        assertThat(summary.recentCount()).isEqualTo(2);
        assertThat(summary.recentAmount()).isEqualByComparingTo("1698.00");
        assertThat(summary.totalCount()).isGreaterThanOrEqualTo(3);
    }
}
