package com.xncoding.testing.unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.xncoding.testing.order.DuplicateOrderException;
import com.xncoding.testing.order.Order;
import com.xncoding.testing.order.OrderRepository;
import com.xncoding.testing.order.OrderService;
import com.xncoding.testing.order.OrderStatus;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock
    OrderRepository orders;

    @InjectMocks
    OrderService service;

    private static final Order SAMPLE =
            new Order(1L, "SK-T4-1", new BigDecimal("359.00"), OrderStatus.NEW, Instant.now());

    @Test
    void creates_order_with_new_status() {
        when(orders.existsByOrderNo("SK-T4-1")).thenReturn(false);
        when(orders.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        Order created = service.create("SK-T4-1", new BigDecimal("359.00"));

        assertThat(created.status()).isEqualTo(OrderStatus.NEW);
        assertThat(created.orderNo()).isEqualTo("SK-T4-1");
        verify(orders).insert(any());
    }

    @Test
    void rejects_duplicate_order_no_without_touching_repository() {
        when(orders.existsByOrderNo("SK-DUP")).thenReturn(true);

        assertThatThrownBy(() -> service.create("SK-DUP", BigDecimal.ONE))
                .isInstanceOf(DuplicateOrderException.class);
        verify(orders, never()).insert(any());
    }

    @Test
    void pays_new_order_and_marks_paid() {
        when(orders.findById(1L)).thenReturn(Optional.of(SAMPLE));
        when(orders.updateStatus(1L, OrderStatus.PAID)).thenReturn(1);

        Order paid = service.pay(1L);

        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.id()).isEqualTo(1L);
    }

    @Test
    void refuses_to_pay_paid_order() {
        when(orders.findById(1L)).thenReturn(Optional.of(SAMPLE.paid()));

        assertThatThrownBy(() -> service.pay(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAID");
        verify(orders, never()).updateStatus(any(), any());
    }

    @TestFactory
    Stream<DynamicTest> list_filters_by_status() {
        record Case(String name, OrderStatus status, int expectedSize) {
        }
        List<Case> cases = List.of(
                new Case("不传状态返回全部", null, 3),
                new Case("只取 NEW", OrderStatus.NEW, 2),
                new Case("只取 PAID", OrderStatus.PAID, 1),
                new Case("只取 CANCELLED", OrderStatus.CANCELLED, 0));
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            when(orders.findAll(c.status())).thenReturn(
                    java.util.Collections.nCopies(c.expectedSize(), SAMPLE));
            assertThat(service.list(c.status())).hasSize(c.expectedSize());
        }));
    }
}
