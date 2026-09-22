package com.xncoding.transaction.controller;

import com.xncoding.transaction.domain.TxEvent;
import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.exception.BalanceNotEnoughException;
import com.xncoding.transaction.service.IsolationService;
import com.xncoding.transaction.service.ProgrammaticService;
import com.xncoding.transaction.service.ReadOnlyService;
import com.xncoding.transaction.service.TransactionEventService;
import com.xncoding.transaction.support.ScenarioRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 前面几组之外的几件事：只读事务、隔离级别、编程式事务、事务事件。
 *
 * <p>放在一个类里，是因为它们都有一个共同点：不是「会不会回滚」的问题，
 * 而是「事务这个东西在内核上是怎么被搭出来的」的问题。
 * 只读和隔离级别考验的是「Spring 到底有没有把声明落到连接上」，
 * 编程式考验的是「谁在控制事务边界」，事务事件考验的是「事务的生命周期有几个时刻」。
 */
@RestController
@RequestMapping("/api")
public class AdvancedController {

    private final ReadOnlyService readOnlyService;
    private final IsolationService isolationService;
    private final ProgrammaticService programmaticService;
    private final TransactionEventService eventService;
    private final ScenarioRunner runner;

    public AdvancedController(ReadOnlyService readOnlyService,
                              IsolationService isolationService,
                              ProgrammaticService programmaticService,
                              TransactionEventService eventService,
                              ScenarioRunner runner) {
        this.readOnlyService = readOnlyService;
        this.isolationService = isolationService;
        this.programmaticService = programmaticService;
        this.eventService = eventService;
        this.runner = runner;
    }

    // ---------------------------------------------------------------- 只读事务

    @GetMapping("/readonly/writable")
    public ScenarioResult writable() {
        return runner.record(
                "只读事务 · 对照组（没打 readOnly）",
                "预期：Spring 声明为 false，数据库会话也是可读写",
                "基准组。下面三条都和它对照。",
                steps -> readOnlyService.writableRead(steps));
    }

    @GetMapping("/readonly/read")
    public ScenarioResult readOnly() {
        return runner.record(
                "只读事务 · 只读查询",
                "预期：Spring 声明为 true",
                "readOnly 的标记确实设上了。接下来要看的是它有没有进一步落到数据库连接上。",
                steps -> readOnlyService.readOnlyRead(steps));
    }

    @GetMapping("/readonly/write")
    public ScenarioResult readOnlyWrite() {
        return runner.record(
                "只读事务 · 在只读事务里写入",
                "预期：写操作被拦下，抛 TransientDataAccessResourceException，事务回滚",
                "两组读数都是真的：Spring 侧声明了 readOnly，数据库会话也变成了只读。"
                        + "但真正拦下这次写入的既不是 Spring 也不是 MySQL，而是 JDBC 驱动 ——"
                        + "MySQL Connector/J 在自己的 StatementImpl 里加了一道客户端校验，"
                        + "连接是只读的就不让执行 UPDATE。所以这条防线依赖驱动实现，"
                        + "换个驱动或连接池就不一定还在，别把它当安全机制用。"
                        + "另外注意异常是 RuntimeException，事务顺带回滚了 ——"
                        + "和「受检异常不回滚」那条规则不要混着记。",
                steps -> readOnlyService.readOnlyWrite(steps));
    }

    // ---------------------------------------------------------------- 隔离级别

    /**
     * 四种隔离级别一次看全。
     *
     * <p>四个方法各自开一个事务，所以不能塞在一个事务里调用 —— 那样后三个会「加入已有事务」，
     * 连 SET 语句都不会执行。这个接口由 Controller 逐个调用，每次都是新事务。
     */
    @GetMapping("/isolation")
    public ScenarioResult isolation() {
        return runner.record(
                "隔离级别 · 四种声明方式对照",
                "预期：DEFAULT 不改连接；其余三种把 SET 落到连接上",
                "重点看 DEFAULT 那一行：Spring 侧读到 null，数据库会话仍是 MySQL 默认的 "
                        + "REPEATABLE-READ。它和显式写 REPEATABLE_READ 的结果一样，"
                        + "但过程完全不同 —— 换一个默认级别不是 RR 的数据库，"
                        + "两者的行为就分开了。另外，隔离级别只在新事务里下得去，"
                        + "加进外层已有事务时会被静默忽略。",
                steps -> {
                    isolationService.defaultLevel(steps);
                    isolationService.readCommitted(steps);
                    isolationService.repeatableRead(steps);
                    isolationService.serializable(steps);
                });
    }

    // ---------------------------------------------------------------- 编程式事务

    @PostMapping("/programmatic/commit")
    public ScenarioResult templateCommit() {
        return runner.record(
                "编程式 · 正常提交",
                "预期：提交，熊大 900.00",
                "TransactionTemplate 默认传播行为是 REQUIRED，和 @Transactional 一致。",
                steps -> programmaticService.templateCommit(steps));
    }

    @PostMapping("/programmatic/rollback-by-flag")
    public ScenarioResult templateRollbackByFlag() {
        return runner.record(
                "编程式 · setRollbackOnly 回滚",
                "预期：回滚，余额保持 1000.00 / 500.00；HTTP 200，不抛异常",
                "声明式事务做不到这件事：它的回滚只有「抛异常」一个出口，"
                        + "没法让方法正常返回同时回滚。编程式可以直接改事务状态。"
                        + "批量导入里「收集每条失败原因、最后整体回滚」就是靠它实现的。",
                steps -> programmaticService.templateRollbackByFlag(steps));
    }

    @PostMapping("/programmatic/rollback-by-exception")
    public ScenarioResult templateRollbackByException() {
        return runner.record(
                "编程式 · 抛异常回滚",
                "预期：回滚，余额保持 1000.00 / 500.00",
                "和声明式一致。异常从 execute 里穿出来，模板负责回滚。",
                steps -> programmaticService.templateRollbackByException(steps));
    }

    @PostMapping("/programmatic/rollback-by-wrapped-checked")
    public ScenarioResult templateRollbackByWrappedChecked() {
        return runner.record(
                "编程式 · 受检异常（必须包装）",
                "预期：回滚。和声明式的行为相反",
                "这是编程式和声明式最实质的一个差别：受检异常在这里是必须包装的"
                        + "（TransactionCallback 没声明 throws），包完就成了运行时异常，"
                        + "于是必然回滚。「受检异常默认不回滚」这个坑在编程式事务里不存在。",
                steps -> programmaticService.templateRollbackByWrappedChecked(steps));
    }

    @PostMapping("/programmatic/anonymous-class")
    public ScenarioResult templateAnonymousClass() {
        return runner.record(
                "编程式 · 匿名内部类写法",
                "预期：提交，熊大 900.00",
                "和 lambda 完全等价，写出来是为了能看清 TransactionCallbackWithoutResult "
                        + "的方法签名 —— 那个签名里没有 throws Exception，"
                        + "上面那条结论就是从它来的。",
                steps -> programmaticService.templateAnonymousClass(steps));
    }

    @PostMapping("/programmatic/requires-new")
    public ScenarioResult templateRequiresNew() {
        return runner.record(
                "编程式 · 在外层事务里开 REQUIRES_NEW",
                "预期：外层回滚、内层留下。熊大 1000.00，熊三 250.00",
                "审计日志、风控记录这类数据必须立刻落库时用得上。"
                        + "编程式的优势在这里很直观：换一个模板对象就换了传播行为，"
                        + "不需要为了一个事务边界把方法拆到另一个 Bean 里去。",
                steps -> programmaticService.requiresNewInsideTransaction(steps));
    }

    // ---------------------------------------------------------------- 事务事件

    @PostMapping("/events/commit")
    public ScenarioResult eventsCommit() {
        eventService.clearEvents();
        return runner.record(
                "事务事件 · 事务提交",
                "预期：BEFORE_COMMIT、AFTER_COMMIT、AFTER_COMPLETION，没有 AFTER_ROLLBACK；"
                        + "因为挂了两个 AFTER_COMMIT 监听器，一共 4 条",
                "提交路径上各相位的分工。要看清两件事："
                        + "一是 BEFORE_COMMIT 那条和业务数据在同一条事务里，业务数据留下它就留下；"
                        + "二是同一个相位挂多个监听器时会都执行 —— 事件表里那两条 AFTER_COMMIT "
                        + "就是两个不同的监听器写的，括号里注明了各自的身份。",
                steps -> {
                    eventService.publishAndCommit(steps);
                    appendEvents(steps);
                });
    }

    @PostMapping("/events/rollback")
    public ScenarioResult eventsRollback() {
        eventService.clearEvents();
        return runner.record(
                "事务事件 · 事务回滚",
                "预期：BEFORE_COMMIT 那条消失了，只剩 AFTER_ROLLBACK 和 AFTER_COMPLETION，共 2 条",
                "这一条是整节的重点。BEFORE_COMMIT 的监听器确实执行了、SQL 确实发出去了，"
                        + "但它的写入在业务事务里，业务一回滚它就没了。"
                        + "所以「在 BEFORE_COMMIT 里发短信、调第三方接口」是有去无回的 —— "
                        + "外部系统不会跟着你回滚。有副作用的操作要放到 AFTER_COMMIT。",
                steps -> {
                    try {
                        eventService.publishAndRollback(steps);
                    } catch (BalanceNotEnoughException e) {
                        // 这个异常是故意抛的，得接住 —— 否则下面读事件表这一步就走不到了，
                        // 而「回滚之后事件表里还剩什么」恰恰是本节唯一要看的证据。
                        steps.add(new StepResult("事务已回滚", "已出事务边界，事务已经处理完了",
                                "异常：" + e.getClass().getSimpleName()));
                    }
                    appendEvents(steps);
                });
    }

    @PostMapping("/events/no-transaction")
    public ScenarioResult eventsWithoutTransaction() {
        eventService.clearEvents();
        return runner.record(
                "事务事件 · 没有事务的时候",
                "预期：只有开了 fallbackExecution 的那个监听器写了记录，共 1 条",
                "@TransactionalEventListener 默认没有事务就直接丢弃事件，"
                        + "只有显式打开 fallbackExecution 才会执行。这个默认值踩过一次就忘不了。"
                        + "拿它和上面「事务提交」那一次的 4 条对比着看更清楚："
                        + "同一批监听器，有事务时四个相位都动，没事务时只有那一个肯干活。",
                steps -> {
                    eventService.publishWithoutTransaction(steps);
                    appendEvents(steps);
                });
    }

    /** 直接看事件表。每个事件接口自己会清表，所以这里看到的是最后一次实验的结果。 */
    @GetMapping("/events")
    public List<TxEvent> events() {
        return eventService.events();
    }

    /** 把事件表当前内容追加成一条步骤。事务早已结束，读的是落库后的真实结果。 */
    private void appendEvents(List<StepResult> steps) {
        List<TxEvent> rows = eventService.events();
        String phases = rows.isEmpty()
                ? "空（一条都没有）"
                : rows.stream().map(TxEvent::getPhase).collect(Collectors.joining("、"));
        steps.add(new StepResult("事务结束后查事件表",
                "已出事务边界，读到的是落库后的真实结果",
                "共 " + rows.size() + " 条：" + phases));
    }
}
