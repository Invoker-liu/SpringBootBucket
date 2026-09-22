package com.xncoding.multisource.context;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 当前线程正在用哪个数据源。
 *
 * <p>三个设计决定，每个都有具体理由：
 *
 * <p><b>1. 用 {@link ThreadLocal} 而不是 {@code InheritableThreadLocal}。</b>
 * 可继承的那个版本只在线程<b>被创建</b>的那一刻把父线程的值拷一份，
 * 之后再怎么切父线程都不影响子线程。Web 容器的线程池是长期存活的，
 * 池里那些线程早就创建完了，切数据源对它们毫无影响 —— 表现出来就是
 * 「有时候对、有时候错、重启一下又好了」这种最难查的毛病。
 * 真要在异步里指定数据源，就显式把 key 当参数传进去。
 *
 * <p><b>2. 用栈（双端队列）而不是存单个值。</b>
 * {@code @DataSource} 允许嵌套：外层方法标 BIZ，里面调的方法标 POS，
 * 里层跑完必须能回到 BIZ。单值存不下这个状态，栈可以。
 *
 * <p><b>3. 弹干净之后要把 ThreadLocal 本身也 remove 掉。</b>
 * 池化线程会被反复复用，留一个空队列在上面本身不致命，
 * 但配合「用完必须清」的纪律更容易在评审时被一眼看懂，
 * 也避免以后有人往队列里塞了没弹干净的东西却查不出来。
 */
public final class DataSourceContextHolder {

    private static final ThreadLocal<Deque<DataSourceKey>> HOLDER =
            ThreadLocal.withInitial(ArrayDeque::new);

    private DataSourceContextHolder() {
    }

    /** 压入一个数据源标识，切换生效。 */
    public static void push(DataSourceKey key) {
        HOLDER.get().push(key);
    }

    /** 弹出栈顶，回到上一层的数据源。 */
    public static void poll() {
        Deque<DataSourceKey> deque = HOLDER.get();
        deque.poll();
        if (deque.isEmpty()) {
            HOLDER.remove();
        }
    }

    /**
     * 当前生效的标识，没设置过就是 {@code null}。
     *
     * <p>返回 null 时怎么办由路由数据源决定：默认会回退到
     * {@code defaultTargetDataSource}，把 {@code lenientFallback} 关掉则会直接报错。
     * 见 {@code routing.DynamicDataSource}。
     */
    public static DataSourceKey peek() {
        return HOLDER.get().peek();
    }

    /** 清空当前线程的整个栈。拦截器兜底用。 */
    public static void clear() {
        HOLDER.remove();
    }
}
