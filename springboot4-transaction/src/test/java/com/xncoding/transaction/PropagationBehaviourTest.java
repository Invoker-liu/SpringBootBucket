package com.xncoding.transaction;

import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.service.PropagationOuterService;
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
 * 传播行为。
 *
 * <p>外层固定操作 1 号（熊大）→ 2 号（熊二）账户 100.00，
 * 内层固定操作 3 号（熊三）→ 4 号（熊四）账户 50.00。
 * 两组账户分开是为了避开行锁 —— 否则 REQUIRES_NEW 那条新连接会去等外层还没提交的行锁，
 * 实验会卡在 InnoDB 的锁等待上，看到的是锁的行为而不是传播行为。
 *
 * <p>所以判断标准很直接：<b>看熊大还是不是 1000.00（外层回没回滚），
 * 熊三还是不是 300.00（内层回没回滚）。</b>
 */
@SpringBootTest
@ActiveProfiles("test")
class PropagationBehaviourTest {

    @Autowired
    private PropagationOuterService outer;

    @Autowired
    private ScenarioRunner runner;

    /**
     * 最容易踩的一个：catch 住了，以为没事。
     */
    @Test
    @DisplayName("内层 REQUIRED 失败 + 外层 catch：提交时抛 UnexpectedRollbackException，两边都回滚")
    void requiredInnerFailsCaught() {
        ScenarioResult result = runner.record("t", "e", "c", outer::requiredInnerFailsCaught);

        assertThat(thrownException(result))
                .as("异常名很准：你没打算回滚，但事务已经只能回滚了")
                .isEqualTo("UnexpectedRollbackException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertThat(balance(result, "熊三"))
                .as("只要还是 1000.00，就说明外层那笔也被带走了 —— 内外层本来就是同一条事务")
                .isEqualByComparingTo("300.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("内层 REQUIRED 失败不 catch：异常穿出，整体回滚")
    void requiredInnerFailsNotCaught() {
        ScenarioResult result = runner.record("t", "e", "c", outer::requiredInnerFailsNotCaught);

        assertThat(thrownException(result)).isEqualTo("BalanceNotEnoughException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertThat(balance(result, "熊三")).isEqualByComparingTo("300.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("对照组：内外层都成功，一起提交")
    void requiredInnerSucceeds() {
        ScenarioResult result = runner.record("t", "e", "c", outer::requiredInnerSucceeds);

        assertThat(thrownException(result)).isNull();
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertThat(balance(result, "熊三")).isEqualByComparingTo("250.00");
        assertTotalUnchanged(result);
    }

    /**
     * 和 {@link #requiredInnerFailsCaught()} 逐字对照的一条。
     *
     * <p>两次都 catch 了，一次外层提交不了、一次提交得了。差别只在「内层是不是同一条事务」。
     */
    @Test
    @DisplayName("内层 REQUIRES_NEW 失败 + 外层 catch：外层提交成功，只有内层回滚")
    void requiresNewInnerFailsCaught() {
        ScenarioResult result = runner.record("t", "e", "c", outer::requiresNewInnerFailsCaught);

        assertThat(thrownException(result)).isNull();
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertThat(balance(result, "熊三")).isEqualByComparingTo("300.00");
        assertTotalUnchanged(result);
    }

    /**
     * 结果和 REQUIRES_NEW 一样，机制完全不同：同一条连接、同一个事务，靠 savepoint 局部回退。
     *
     * <p>日志里的分野是 {@code Creating nested transaction with name [...]} ——
     * REQUIRES_NEW 走的是「挂起 + 新开交易」，NESTED 走的是这句。
     */
    @Test
    @DisplayName("内层 NESTED 失败 + 外层 catch：外层提交成功，内层退回 savepoint")
    void nestedInnerFailsCaught() {
        ScenarioResult result = runner.record("t", "e", "c", outer::nestedInnerFailsCaught);

        assertThat(thrownException(result)).isNull();
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertThat(balance(result, "熊三")).isEqualByComparingTo("300.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("内层 NESTED 成功：savepoint 白打了一个，一起提交")
    void nestedInnerSucceeds() {
        ScenarioResult result = runner.record("t", "e", "c", outer::nestedInnerSucceeds);

        assertThat(thrownException(result)).isNull();
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertThat(balance(result, "熊三")).isEqualByComparingTo("250.00");
        assertTotalUnchanged(result);
    }

    /**
     * 最让人懵的一种结果：同一个调用链里的两笔写操作，一笔没了、一笔留着。
     */
    @Test
    @DisplayName("内层 NOT_SUPPORTED：外层回滚了，内层那笔却留在库里")
    void notSupportedInnerCommitsIndependently() {
        ScenarioResult result = runner.record("t", "e", "c", outer::notSupportedInnerCommitsIndependently);

        assertThat(thrownException(result)).isEqualTo("BalanceNotEnoughException");
        assertThat(balance(result, "熊大"))
                .as("外层抛异常了，那 100.00 回滚")
                .isEqualByComparingTo("1000.00");
        assertThat(balance(result, "熊三"))
                .as("内层在事务外跑，UPDATE 执行完就自动提交，外层回滚带不走它")
                .isEqualByComparingTo("250.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("MANDATORY 但调用方没有事务：抛 IllegalTransactionStateException")
    void mandatoryWithoutTransaction() {
        ScenarioResult result = runner.record("t", "e", "c", outer::mandatoryWithoutTransaction);

        assertThat(thrownException(result)).isEqualTo("IllegalTransactionStateException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("NEVER 但调用方有事务：抛 IllegalTransactionStateException，外层一起回滚")
    void neverInsideTransaction() {
        ScenarioResult result = runner.record("t", "e", "c", outer::neverInsideTransaction);

        assertThat(thrownException(result)).isEqualTo("IllegalTransactionStateException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("SUPPORTS 在无事务时：不开事务，内层那笔立即提交")
    void supportsOutsideTransaction() {
        ScenarioResult result = runner.record("t", "e", "c", outer::supportsOutsideTransaction);

        assertThat(thrownException(result)).isNull();
        assertThat(balance(result, "熊三")).isEqualByComparingTo("250.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("SUPPORTS 在有事务时：加入外层事务")
    void supportsInsideTransaction() {
        ScenarioResult result = runner.record("t", "e", "c", outer::supportsInsideTransaction);

        assertThat(thrownException(result)).isNull();
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertThat(balance(result, "熊三")).isEqualByComparingTo("250.00");
        assertTotalUnchanged(result);
    }
}
