package com.xncoding.transaction;

import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.service.RollbackService;
import com.xncoding.transaction.support.ScenarioRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static com.xncoding.transaction.TestSupport.assertTotalUnchanged;
import static com.xncoding.transaction.TestSupport.balance;
import static com.xncoding.transaction.TestSupport.thrownException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认回滚规则。
 *
 * <p>这一组的断言方式和别处不太一样：不看异常、只看<b>余额</b>。
 * 因为最要命的那个结论是「异常抛出去了，钱也扣了」——
 * 用 {@code assertThatThrownBy} 只能验证前半句，验证不了后半句。
 */
@SpringBootTest
@ActiveProfiles("test")
class RollbackRuleTest {

    @Autowired
    private RollbackService rollbackService;

    @Autowired
    private ScenarioRunner runner;

    @Test
    @DisplayName("不抛异常：提交，熊大少 100.00、熊二多 100.00")
    void plainCommit() {
        ScenarioResult result = runner.record("t", "e", "c", steps -> rollbackService.plainCommit());

        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertThat(balance(result, "熊二")).isEqualByComparingTo("600.00");
        assertThat(thrownException(result)).isNull();
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("运行时异常：默认回滚，余额一分不动")
    void runtimeExceptionRollsBack() {
        ScenarioResult result = runner.record("t", "e", "c",
                steps -> rollbackService.runtimeExceptionRollsBack());

        assertThat(thrownException(result)).isEqualTo("BalanceNotEnoughException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertThat(balance(result, "熊二")).isEqualByComparingTo("500.00");
        assertTotalUnchanged(result);
    }

    /**
     * 本篇最重要的一条断言。
     *
     * <p>它挂掉的方式很能说明问题：不是「异常没抛出来」，而是「余额不对」。
     */
    @Test
    @DisplayName("受检异常：默认【不】回滚 —— 异常照抛，但钱已经扣了")
    void checkedExceptionCommits() {
        ScenarioResult result = runner.record("t", "e", "c",
                steps -> rollbackService.checkedExceptionCommits());

        assertThat(thrownException(result))
                .as("受检异常仍然会传播给调用方，所以调用方会以为失败了")
                .isEqualTo("TransferRejectedException");
        assertThat(balance(result, "熊大"))
                .as("但事务提交了，钱已经扣了。这就是「接口说失败、数据说成功」")
                .isEqualByComparingTo("900.00");
        assertThat(balance(result, "熊二")).isEqualByComparingTo("600.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("补上 rollbackFor 之后，同一个受检异常就回滚了")
    void checkedExceptionRollsBackWithRollbackFor() {
        ScenarioResult result = runner.record("t", "e", "c",
                steps -> rollbackService.checkedExceptionRollsBackWithRollbackFor());

        assertThat(thrownException(result)).isEqualTo("TransferRejectedException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("noRollbackFor：把默认会回滚的运行时异常改成不回滚")
    void noRollbackFor() {
        ScenarioResult result = runner.record("t", "e", "c",
                steps -> rollbackService.runtimeExceptionSurvivesWithNoRollbackFor());

        assertThat(thrownException(result))
                .as("noRollbackFor 只改回滚判定，不改变异常的传播")
                .isEqualTo("BalanceNotEnoughException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("rollbackFor = Exception.class：受检异常也一起回滚")
    void exceptionClassRollsBackEverything() {
        ScenarioResult result = runner.record("t", "e", "c",
                steps -> rollbackService.exceptionClassRollsBackEverything());

        assertThat(thrownException(result)).isEqualTo("TransferRejectedException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }
}
