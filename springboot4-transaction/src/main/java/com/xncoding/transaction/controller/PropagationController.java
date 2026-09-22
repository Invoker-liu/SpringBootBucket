package com.xncoding.transaction.controller;

import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.service.PropagationOuterService;
import com.xncoding.transaction.support.ScenarioRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 传播行为。七个传播级别里，常用的只有三个：{@code REQUIRED}（默认）、
 * {@code REQUIRES_NEW}、{@code NESTED}。另外四个
 * （{@code SUPPORTS} / {@code NOT_SUPPORTED} / {@code MANDATORY} / {@code NEVER}）
 * 主要是「表达意图 + 出错时立刻报错」，用得少但都配了接口，方便一次看全。
 *
 * <p>读结果的办法：外层动 1、2 号账户，内层动 3、4 号账户，所以看余额就能分辨
 * 「谁留下来了」。每个接口的 {@code expectation} 字段写的是预期，{@code conclusion}
 * 写的是对照结论，对不上就说明理解有偏差。
 */
@RestController
@RequestMapping("/api/propagation")
public class PropagationController {

    private final PropagationOuterService outer;
    private final ScenarioRunner runner;

    public PropagationController(PropagationOuterService outer, ScenarioRunner runner) {
        this.outer = outer;
        this.runner = runner;
    }

    /** REQUIRED + REQUIRED，内层失败被 catch。 */
    @PostMapping("/required-inner-fails-caught")
    public ScenarioResult requiredInnerFailsCaught() {
        return runner.record(
                "传播行为 · 内层 REQUIRED 失败，外层 catch",
                "预期：外层提交不了，抛 UnexpectedRollbackException；两边余额都不变",
                "内外层是同一条事务。内层失败时事务被标成 rollback-only，"
                        + "这个标记不会随异常一起被 catch 掉，所以直到提交的那一刻才发现问题。"
                        + "看到 UnexpectedRollbackException 就去内层找那个被吞掉的异常。",
                steps -> outer.requiredInnerFailsCaught(steps));
    }

    /** REQUIRED + REQUIRED，内层失败不 catch。 */
    @PostMapping("/required-inner-fails-not-caught")
    public ScenarioResult requiredInnerFailsNotCaught() {
        return runner.record(
                "传播行为 · 内层 REQUIRED 失败，不 catch",
                "预期：整体回滚，两边余额都不变",
                "异常一路穿到最外面，事务按默认规则回滚，行为符合直觉 —— "
                        + "这也是最该用的写法：别 catch 完了不处理。",
                steps -> outer.requiredInnerFailsNotCaught(steps));
    }

    /** 对照组：都成功。 */
    @PostMapping("/required-inner-succeeds")
    public ScenarioResult requiredInnerSucceeds() {
        return runner.record(
                "传播行为 · 内外层都成功",
                "预期：全部提交。熊大 900.00，熊三 250.00",
                "基准组。它证明实验环境和调用链本身没问题。",
                steps -> outer.requiredInnerSucceeds(steps));
    }

    /** REQUIRES_NEW 失败被 catch。 */
    @PostMapping("/requires-new-inner-fails-caught")
    public ScenarioResult requiresNewInnerFailsCaught() {
        return runner.record(
                "传播行为 · 内层 REQUIRES_NEW 失败，外层 catch",
                "预期：外层提交成功，内层回滚。熊大 900.00，熊三仍是 300.00",
                "和上一条 REQUIRED 的写法逐字对照：同样是 catch，这次外层提交成功了。"
                        + "差别只有一个 —— 内层是独立事务，它回滚不动外层。"
                        + "代价是多占一条数据库连接。",
                steps -> outer.requiresNewInnerFailsCaught(steps));
    }

    /** NESTED 失败被 catch。 */
    @PostMapping("/nested-inner-fails-caught")
    public ScenarioResult nestedInnerFailsCaught() {
        return runner.record(
                "传播行为 · 内层 NESTED 失败，外层 catch",
                "预期：外层提交成功，内层退回 savepoint。熊大 900.00，熊三仍是 300.00",
                "结果和 REQUIRES_NEW 看起来一样，机制完全不同：NESTED 全程一条连接、一个事务，"
                        + "内层失败只是回滚到 savepoint，外层照常提交。"
                        + "外层如果想连内层的失败一起回滚，把异常抛出去就行；"
                        + "REQUIRES_NEW 做不到这一点 —— 它已经提交了。",
                steps -> outer.nestedInnerFailsCaught(steps));
    }

    /** NESTED 成功。 */
    @PostMapping("/nested-inner-succeeds")
    public ScenarioResult nestedInnerSucceeds() {
        return runner.record(
                "传播行为 · 内层 NESTED 成功",
                "预期：全部提交。熊大 900.00，熊三 250.00",
                "NESTED 成功时和 REQUIRED 看不出区别，都是提交。"
                        + "两者的差异只在失败路径上才显形。",
                steps -> outer.nestedInnerSucceeds(steps));
    }

    /** NOT_SUPPORTED：内层脱离事务。 */
    @PostMapping("/not-supported-inner")
    public ScenarioResult notSupportedInner() {
        return runner.record(
                "传播行为 · 内层 NOT_SUPPORTED",
                "预期：外层 100.00 回滚，内层 50.00 留下。熊大 1000.00，熊三 250.00",
                "这是最容易让人懵的一种结果：同一个方法调用链里的两笔写操作，"
                        + "一笔没了、一笔留着。因为 NOT_SUPPORTED 把外层事务挂起，"
                        + "内层的 UPDATE 在自己的连接上跑，执行完就自动提交了。",
                steps -> outer.notSupportedInnerCommitsIndependently(steps));
    }

    /** MANDATORY 在没有事务时被调用。 */
    @PostMapping("/mandatory-without-transaction")
    public ScenarioResult mandatoryWithoutTransaction() {
        return runner.record(
                "传播行为 · MANDATORY 但没有事务",
                "预期：抛 IllegalTransactionStateException，业务代码一行都没执行",
                "MANDATORY 的作用就是把「我以为有人给我开了事务」变成一条明确的错误。"
                        + "它比 REQUIRED 严格：REQUIRED 会自己开一个，MANDATORY 不会。",
                steps -> outer.mandatoryWithoutTransaction(steps));
    }

    /** NEVER 在有事务时被调用。 */
    @PostMapping("/never-inside-transaction")
    public ScenarioResult neverInsideTransaction() {
        return runner.record(
                "传播行为 · NEVER 但外面有事务",
                "预期：抛 IllegalTransactionStateException，外层那 100.00 一起回滚",
                "NEVER 和 MANDATORY 是一对反向的断言。它常用来标记那些"
                        + "「一旦被放进事务就会出问题」的方法，比如会自己调 DDL、"
                        + "或者依赖自动提交的行为。",
                steps -> outer.neverInsideTransaction(steps));
    }

    /** SUPPORTS，无事务。 */
    @PostMapping("/supports-outside-transaction")
    public ScenarioResult supportsOutsideTransaction() {
        return runner.record(
                "传播行为 · SUPPORTS（无事务时）",
                "预期：不开事务，内层那 50.00 立即提交。熊三 250.00",
                "SUPPORTS 从不主动开事务：有就用、没有就不用。"
                        + "适合「被事务调用时可以一起回滚、被非事务调用时也能跑」的查询或辅助逻辑。",
                steps -> outer.supportsOutsideTransaction(steps));
    }

    /** SUPPORTS，有事务。 */
    @PostMapping("/supports-inside-transaction")
    public ScenarioResult supportsInsideTransaction() {
        return runner.record(
                "传播行为 · SUPPORTS（有事务时）",
                "预期：加入外层事务。熊大 900.00，熊三 250.00",
                "和上一条对照：同一段代码，有没有外层事务决定了它到底在不在事务里。"
                        + "探针读出来的状态完全不同。",
                steps -> outer.supportsInsideTransaction(steps));
    }
}
