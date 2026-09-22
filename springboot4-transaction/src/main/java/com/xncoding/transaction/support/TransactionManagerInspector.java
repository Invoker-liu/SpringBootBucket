package com.xncoding.transaction.support;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把容器里的事务管理器列出来。
 *
 * <p>「名字写错」那一节需要先证明容器里到底有几个事务管理器、分别叫什么名字。
 * 靠猜是不行的：Boot 自动配置给你的那个 bean 名字由它的 {@code @Bean} 方法名决定
 * （这里就是 {@code transactionManager}），不写名字的 {@code @Transactional}
 * 走的是「按类型找唯一的那个」，两条路的规则完全不同。
 *
 * <p>注入 {@code Map<String, PlatformTransactionManager>} 而不是 List，
 * 是因为要的正是「bean 名 → 实例」这个映射，名字本身就是这一节的主角。
 */
@Component
public class TransactionManagerInspector {

    private final Map<String, PlatformTransactionManager> managers;

    public TransactionManagerInspector(Map<String, PlatformTransactionManager> managers) {
        this.managers = managers;
    }

    public String describe() {
        if (managers.isEmpty()) {
            return "容器里没有事务管理器";
        }
        return managers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(e -> "%s（%s）".formatted(e.getKey(), e.getValue().getClass().getSimpleName()))
                .collect(Collectors.joining("、"));
    }

    public int count() {
        return managers.size();
    }
}
