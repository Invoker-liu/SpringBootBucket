package com.xncoding.testing.data;

import java.math.BigDecimal;
import java.time.Instant;

import com.xncoding.testing.order.Order;
import com.xncoding.testing.order.OrderRepository;
import com.xncoding.testing.order.OrderStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOCKER_HOST", matches = "tcp://.*")
class OrderRepositoryContainerTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4.7");

    @Autowired
    OrderRepository orders;

    @Test
    void container_is_wireable_and_reachable() {
        System.out.println("TC-INFO containerId=" + mysql.getContainerId()
                + " jdbcUrl=" + mysql.getJdbcUrl()
                + " mappedPort=" + mysql.getMappedPort(3306));
        assertThat(mysql.isRunning()).isTrue();
    }

    @Test
    void insert_and_find_back_through_real_mysql() {
        Order saved = orders.insert(new Order(null, "SK-TC-1",
                new BigDecimal("359.00"), OrderStatus.NEW, Instant.now()));

        Order loaded = orders.findById(saved.id()).orElseThrow();
        assertThat(loaded.orderNo()).isEqualTo("SK-TC-1");
        assertThat(loaded.amount()).isEqualByComparingTo("359.00");
        assertThat(loaded.status()).isEqualTo(OrderStatus.NEW);
        assertThat(saved.id()).isPositive();
    }

    @Test
    void update_status_persists_in_mysql() {
        Order saved = orders.insert(new Order(null, "SK-TC-2",
                new BigDecimal("88.00"), OrderStatus.NEW, Instant.now()));

        orders.updateStatus(saved.id(), OrderStatus.PAID);

        assertThat(orders.findById(saved.id()).orElseThrow().status())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    void delete_removes_row_in_mysql() {
        Order saved = orders.insert(new Order(null, "SK-TC-3",
                new BigDecimal("5.00"), OrderStatus.NEW, Instant.now()));

        assertThat(orders.deleteById(saved.id())).isTrue();
        assertThat(orders.findById(saved.id())).isEmpty();
    }

    @Test
    void unique_order_no_is_enforced_by_database() {
        orders.insert(new Order(null, "SK-TC-UNIQ",
                BigDecimal.ONE, OrderStatus.NEW, Instant.now()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        orders.insert(new Order(null, "SK-TC-UNIQ",
                                BigDecimal.ONE, OrderStatus.NEW, Instant.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
