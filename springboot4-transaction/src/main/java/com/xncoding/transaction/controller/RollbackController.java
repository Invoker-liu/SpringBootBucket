package com.xncoding.transaction.controller;

import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.service.RollbackService;
import com.xncoding.transaction.support.ScenarioRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回滚规则。六个接口，每个都只做一件事：转 100.00，然后按不同的方式结束。
 *
 * <p>看结果的时候只盯一个数：熊大的余额。1000.00 说明回滚了，900.00 说明提交了。
 * 四个账户余额之和始终是 2000.00，转账不改变总额，所以它不能用来判断回滚 ——
 * 这一点容易搞反：总额不变是正常的，变了才是出了 bug。
 *
 * <p>六个接口里 {@code /checked} 那个是最值得看的。它没有报错、没有告警，
 * HTTP 返回 422 告诉调用方「转账被拒」，而数据库里钱已经扣了。
 * 这种「接口说失败、数据说成功」的分裂，是声明式事务默认规则造成的。
 */
@RestController
@RequestMapping("/api/rollback")
public class RollbackController {

    private final RollbackService rollbackService;
    private final ScenarioRunner runner;

    public RollbackController(RollbackService rollbackService, ScenarioRunner runner) {
        this.rollbackService = rollbackService;
        this.runner = runner;
    }

    /** 对照组：不抛异常。 */
    @PostMapping("/plain")
    public ScenarioResult plain() {
        return runner.record(
                "对照组 · 什么都不抛",
                "预期：提交。熊大 900.00，熊二 600.00",
                "事务本身是正常工作的。后面每一个「回滚了没有」的判断，都以这一条为基准 —— "
                        + "先确认事务能提交，否则你看到的「没扣钱」可能是别的原因造成的。",
                steps -> rollbackService.plainCommit());
    }

    /** 运行时异常：默认回滚。 */
    @PostMapping("/runtime")
    public ScenarioResult runtime() {
        return runner.record(
                "默认规则 · 运行时异常",
                "预期：回滚，余额保持 1000.00 / 500.00",
                "默认就回滚。判定逻辑在 DefaultTransactionAttribute#rollbackOn 里，"
                        + "实现只有一句：ex instanceof RuntimeException || ex instanceof Error。",
                steps -> rollbackService.runtimeExceptionRollsBack());
    }

    /** 受检异常：默认不回滚 —— 本篇第一个坑。 */
    @PostMapping("/checked")
    public ScenarioResult checked() {
        return runner.record(
                "默认规则 · 受检异常",
                "直觉预期：回滚。实际预期：提交，熊大 900.00",
                "受检异常不在默认回滚范围内，事务照常提交；异常仍然会抛给调用方，"
                        + "所以调用方会以为失败了。这就是「接口说失败、数据说成功」的来源。",
                steps -> rollbackService.checkedExceptionCommits());
    }

    /** 显式 rollbackFor：受检异常也回滚。 */
    @PostMapping("/checked-rollback-for")
    public ScenarioResult checkedRollbackFor() {
        return runner.record(
                "rollbackFor · 受检异常",
                "预期：回滚，余额保持 1000.00 / 500.00",
                "和上一条逐字对照，唯一差别是多了 rollbackFor = TransferRejectedException.class。"
                        + "规矩可以定得很简单：方法签名上只要出现 throws，rollbackFor 就必须写。",
                steps -> rollbackService.checkedExceptionRollsBackWithRollbackFor());
    }

    /** noRollbackFor：把默认会回滚的运行时异常排除掉。 */
    @PostMapping("/no-rollback-for")
    public ScenarioResult noRollbackFor() {
        return runner.record(
                "noRollbackFor · 运行时异常",
                "预期：不回滚，熊大 900.00",
                "noRollbackFor 的作用正好相反：让一个默认会回滚的异常变得不回滚。"
                        + "注意它只改回滚判定，不改变异常传播 —— 异常照样抛出去。",
                steps -> rollbackService.runtimeExceptionSurvivesWithNoRollbackFor());
    }

    /** rollbackFor = Exception.class：一刀切。 */
    @PostMapping("/exception-class")
    public ScenarioResult exceptionClass() {
        return runner.record(
                "rollbackFor = Exception.class",
                "预期：回滚，余额保持 1000.00 / 500.00",
                "受检异常也回滚。这是很多人绕开默认规则的首选写法，"
                        + "但它只作用于当前方法；想全局改掉得动 @EnableTransactionManagement，"
                        + "见 /api/programmatic 那一组。",
                steps -> rollbackService.exceptionClassRollsBackEverything());
    }
}
