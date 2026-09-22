package com.xncoding.testing.junit6;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.xncoding.testing.order.OrderStatus;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
class JUnit6FeaturesTest {

    final List<String> threadNames = new ArrayList<>();
    final List<String> lifecycle = new ArrayList<>();

    @org.junit.jupiter.api.BeforeAll
    void beforeAll() {
        lifecycle.add("beforeAll");
    }

    @Test
    void non_static_before_all_is_per_class_lifecycle() {
        lifecycle.add("test");
        assertThat(lifecycle).containsExactly("beforeAll", "test");
    }

    @RepeatedTest(4)
    void concurrent_execution_uses_multiple_threads() {
        synchronized (threadNames) {
            threadNames.add(Thread.currentThread().getName());
        }
        assertThat(Thread.currentThread().getName()).isNotBlank();
    }

    @TestFactory
    Stream<DynamicTest> order_status_names_come_from_enum() {
        return Stream.of(OrderStatus.values()).map(status ->
                DynamicTest.dynamicTest("状态 " + status + " 可被解析", () ->
                        assertThat(OrderStatus.valueOf(status.name())).isEqualTo(status)));
    }
}
