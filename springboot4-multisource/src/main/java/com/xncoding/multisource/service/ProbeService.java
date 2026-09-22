package com.xncoding.multisource.service;

import com.xncoding.multisource.annotation.DataSource;
import com.xncoding.multisource.context.DataSourceContextHolder;
import com.xncoding.multisource.context.DataSourceKey;
import com.xncoding.multisource.dto.DataSourceProbeResult;
import com.xncoding.multisource.dto.ProbeScenarioResult;
import com.xncoding.multisource.mapper.probe.ProbeUserMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 方案 B 的演示服务：同一套 mapper、同一句 SQL，靠注解在运行时决定去哪个库。
 *
 * <p>这个类里正例和反例是并排的，每个方法都写清楚它想证明什么。
 * 建议的阅读顺序就是方法在文件里的顺序：
 * 基本切换 → 标了没注册的库 → 自调用 → 跨 bean → 事务里切 → 事务外切 → 换线程。
 */
@Service
public class ProbeService {

    private final ProbeUserMapper probeUserMapper;

    private final ProbeInnerService probeInnerService;

    public ProbeService(ProbeUserMapper probeUserMapper, ProbeInnerService probeInnerService) {
        this.probeUserMapper = probeUserMapper;
        this.probeInnerService = probeInnerService;
    }

    // ------------------------------------------------------------------
    // 正向：基本的运行时切换
    // ------------------------------------------------------------------

    @DataSource(DataSourceKey.POS)
    public DataSourceProbeResult probePos() {
        return ProbeQueries.query(probeUserMapper, "标了 @DataSource(POS)", DataSourceKey.POS);
    }

    @DataSource(DataSourceKey.BIZ)
    public DataSourceProbeResult probeBiz() {
        return ProbeQueries.query(probeUserMapper, "标了 @DataSource(BIZ)", DataSourceKey.BIZ);
    }

    // ------------------------------------------------------------------
    // 反例一：标了一个没注册进 targetDataSources 的库
    // ------------------------------------------------------------------

    /**
     * 会抛 {@code DataSourceRoutingException}。
     *
     * <p>关键点在于它是<b>抛异常</b>而不是「安静地跑到默认库」。这个区别就是
     * {@code setLenientFallback(false)} 换来的 —— 默认的 {@code true} 会让这里
     * 一声不吭地返回交易库的数据，接口响应 200，看起来一切正常。
     *
     * <p>另外要提醒一句：这个开关只在 key <b>非 null</b> 时起作用。
     * key 为 null 的情况见 {@link #probeInAnotherThread()}，那里设了 false 也拦不住。
     */
    @DataSource(DataSourceKey.REPORT)
    public DataSourceProbeResult probeUnregisteredKey() {
        return ProbeQueries.query(probeUserMapper, "标了 @DataSource(REPORT)", DataSourceKey.REPORT);
    }

    // ------------------------------------------------------------------
    // 反例二：同类内部调用，注解被 AOP 吃掉
    // ------------------------------------------------------------------

    /**
     * 第 2 步调的是本类自己的 {@link #probePos()}，走的是 {@code this.}，
     * 不经过代理，所以那个 {@code @DataSource(POS)} <b>完全不生效</b>，
     * 查询仍然落在 BIZ 上。
     *
     * <p>把它和 {@link #crossBeanInvocation()} 对着看，一眼就能看出区别。
     */
    @DataSource(DataSourceKey.BIZ)
    public ProbeScenarioResult selfInvocation() {
        List<DataSourceProbeResult> steps = new ArrayList<>();
        steps.add(ProbeQueries.query(probeUserMapper, "第 1 步：外层(B) 直接查", DataSourceKey.BIZ));
        steps.add(probePos());
        steps.add(ProbeQueries.query(probeUserMapper, "第 3 步：回到外层(B)", DataSourceKey.BIZ));
        return new ProbeScenarioResult(
                "selfInvocation",
                "第 2 步应该切到 POS，整体结果 POS/BIZ/BIZ",
                "第 2 步没切过去，三步全在 BIZ —— 同类内部调用绕过了代理",
                steps);
    }

    // ------------------------------------------------------------------
    // 正例：跨 bean 调用
    // ------------------------------------------------------------------

    /**
     * 和第 2 步同样的意图，只是换成调另一个 bean 的方法，注解就生效了。
     *
     * <p>顺带验证了栈式上下文：第 2 步把 POS 压进去、跑完弹掉之后，
     * 第 3 步自动回到外层的 BIZ，不需要手工清理。
     */
    @DataSource(DataSourceKey.BIZ)
    public ProbeScenarioResult crossBeanInvocation() {
        List<DataSourceProbeResult> steps = new ArrayList<>();
        steps.add(ProbeQueries.query(probeUserMapper, "第 1 步：外层(B) 直接查", DataSourceKey.BIZ));
        steps.add(probeInnerService.probePos("第 2 步：跨 bean 调 @DataSource(POS)"));
        steps.add(ProbeQueries.query(probeUserMapper, "第 3 步：回到外层(B)", DataSourceKey.BIZ));
        return new ProbeScenarioResult(
                "crossBeanInvocation",
                "第 2 步切到 POS，第 3 步自动回到 BIZ，整体 BIZ/POS/BIZ",
                "跨 bean 调用切过去了，而且弹栈之后外层没受影响",
                steps);
    }

    // ------------------------------------------------------------------
    // 反例三：在事务内部才切换
    // ------------------------------------------------------------------

    /**
     * {@code @Transactional} 在这个方法上，所以进方法体之前事务已经开了、
     * 连接已经从默认库借出来并绑在当前线程上了。
     * 方法体里再改标识，只是改了一个没人会再去读的变量。
     *
     * <p>第 2 步的 {@code expected} 是 BIZ，但 {@code actualDatabase} 会是
     * {@code springboot4_pos} —— {@code routed()} 为 false，
     * 这就是「注解标了却不生效」最直白的样子。
     */
    @Transactional("dynamicTransactionManager")
    public ProbeScenarioResult switchInsideTransaction() {
        List<DataSourceProbeResult> steps = new ArrayList<>();
        steps.add(ProbeQueries.query(probeUserMapper, "第 1 步：事务已开，还没切", DataSourceKey.POS));

        DataSourceContextHolder.push(DataSourceKey.BIZ);
        try {
            steps.add(ProbeQueries.query(probeUserMapper, "第 2 步：事务内部切到 BIZ 之后", DataSourceKey.BIZ));
        } finally {
            DataSourceContextHolder.poll();
        }

        return new ProbeScenarioResult(
                "switchInsideTransaction",
                "第 2 步应该切到 BIZ，整体 POS/BIZ",
                "第 2 步没切过去，两步都在 POS —— 事务在方法进入时就绑好连接了，"
                        + "之后再改标识也没用。要切换必须把注解标在事务【外面】",
                steps);
    }

    // ------------------------------------------------------------------
    // 正例：切换发生在事务之前
    // ------------------------------------------------------------------

    /**
     * 两个注解标在同一个方法上，谁先执行完全由 advisor 顺序决定。
     *
     * <p>{@code DataSourceAspect} 的 {@code @Order(0)} 比事务 advisor 默认的
     * {@code Ordered.LOWEST_PRECEDENCE} 小，advice 是 order 小的在外层，
     * 所以切换先发生、事务后开，事务借连接时标识已经就绪。
     * 这一条是动态方案能不能用的分水岭，工程里有一个测试专门断言 advisor 的实际顺序。
     */
    @DataSource(DataSourceKey.BIZ)
    @Transactional("dynamicTransactionManager")
    public DataSourceProbeResult switchBeforeTransaction() {
        return ProbeQueries.query(probeUserMapper, "外层切 BIZ + 内层开事务", DataSourceKey.BIZ);
    }

    // ------------------------------------------------------------------
    // 反例四：换了一个线程
    // ------------------------------------------------------------------

    /**
     * 外层明明标了 BIZ，第 2 步丢到另一个线程去查，结果落回了默认库（交易库）。
     *
     * <p>原因是 {@code DataSourceContextHolder} 用的是普通 {@code ThreadLocal}，
     * 换线程就取不到了。而取不到时 {@code peek()} 返回 {@code null}，
     * 父类 {@code AbstractRoutingDataSource.determineTargetDataSource()} 里
     * 有一句
     * <pre>
     *   if (dataSource == null &amp;&amp; (this.lenientFallback || lookupKey == null))
     *       dataSource = this.resolvedDefaultDataSource;
     * </pre>
     * 后半截 {@code lookupKey == null} 是独立的一条路 ——
     * <b>也就是说这里把 {@code lenientFallback} 关掉也拦不住</b>，
     * 它照样安安静静地回退到默认库。想在新线程里用对库，
     * 只能显式把标识传进去，别指望上下文自己过去。
     */
    @DataSource(DataSourceKey.BIZ)
    public ProbeScenarioResult probeInAnotherThread() {
        List<DataSourceProbeResult> steps = new ArrayList<>();
        steps.add(ProbeQueries.query(probeUserMapper, "第 1 步：当前线程(B)", DataSourceKey.BIZ));
        steps.add(CompletableFuture.supplyAsync(
                () -> ProbeQueries.query(probeUserMapper, "第 2 步：另一个线程里查", null)).join());
        steps.add(ProbeQueries.query(probeUserMapper, "第 3 步：回到当前线程(B)", DataSourceKey.BIZ));
        return new ProbeScenarioResult(
                "probeInAnotherThread",
                "第 2 步应该也在 BIZ",
                "第 2 步落回了 springboot4_pos（默认库），而且没有报错。"
                        + "ThreadLocal 不会跨线程传递，lenientFallback=false 对 null 标识也无效",
                steps);
    }
}
