package com.xncoding.transaction;

import com.xncoding.transaction.dto.AccountView;
import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.dto.StepResult;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试用的小工具。
 *
 * <p>这套测试有个共同点：几乎所有的断言都是「做完一个场景之后，某个账户的余额是多少」。
 * 所以把取余额、取最后一步这两件事抽出来，用例本体才读得清楚 ——
 * <b>余额变没变</b>比「抛没抛异常」可靠得多，因为本篇几乎所有的坑都是
 * 「不抛异常但数据错了」。
 */
final class TestSupport {

    private TestSupport() {
    }

    /** 固定初值，和 data.sql 一致。每个用例开始前由 ScenarioRunner 刷回这个状态。 */
    static final BigDecimal INITIAL_XIONG_DA = new BigDecimal("1000.00");
    static final BigDecimal INITIAL_XIONG_ER = new BigDecimal("500.00");
    static final BigDecimal INITIAL_XIONG_SAN = new BigDecimal("300.00");
    static final BigDecimal INITIAL_XIONG_SI = new BigDecimal("200.00");

    /** 转账不改变总额，所以它是一道恒定成立的护栏。 */
    static final BigDecimal TOTAL = new BigDecimal("2000.00");

    static BigDecimal balance(ScenarioResult result, String owner) {
        List<AccountView> accounts = result.accounts();
        return accounts.stream()
                .filter(a -> owner.equals(a.owner()))
                .map(AccountView::balance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果里没有这个账户：" + owner));
    }

    /**
     * 场景过程中抛出的异常会由 ScenarioRunner 记成最后一条步骤，
     * 标签形如「调用抛出 UnexpectedRollbackException」。
     *
     * <p>没有异常时返回 {@code null}。注意「没有任何步骤」也是一种正常情况 ——
     * 比如 {@code plainCommit()} 这种既不加步骤也不抛异常的方法，返回的就是空列表。
     */
    static String thrownException(ScenarioResult result) {
        List<StepResult> steps = result.steps();
        if (steps.isEmpty()) {
            return null;
        }
        String label = steps.get(steps.size() - 1).label();
        return label.startsWith("调用抛出 ") ? label.substring("调用抛出 ".length()) : null;
    }

    /** 每个场景的结果都该满足这条：四个账户加起来还是 2000.00。 */
    static void assertTotalUnchanged(ScenarioResult result) {
        assertThat(result.total())
                .as("转账不该改变四个账户的总额")
                .isEqualByComparingTo(TOTAL);
    }
}
