package com.xncoding.transaction;

import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.failure.ChildThreadService;
import com.xncoding.transaction.failure.ManagerNameService;
import com.xncoding.transaction.failure.NonPublicCaller;
import com.xncoding.transaction.failure.SelfInvocationService;
import com.xncoding.transaction.failure.SwallowedExceptionService;
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
 * 事务失效的五个场景，全部落到断言上。
 *
 * <p>这组用例的价值在于：它们<b>都是绿的就会挂</b>。失效场景的「正确行为」不是抛异常，
 * 而是「注解白写了、钱留下了」。所以每一条都断言余额 ——
 * 断言「钱留下了」才说明我们真的复现了这个坑；哪天框架改了行为、钱不退回了，
 * 用例会红，那正是提醒我们文章该改的时候。
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionalProxyFailureTest {

    @Autowired
    private SelfInvocationService selfInvocationService;
    @Autowired
    private NonPublicCaller nonPublicCaller;
    @Autowired
    private SwallowedExceptionService swallowedExceptionService;
    @Autowired
    private ChildThreadService childThreadService;
    @Autowired
    private ManagerNameService managerNameService;
    @Autowired
    private ScenarioRunner runner;

    // ------------------------------------------------------------ 一、同类内部调用

    @Test
    @DisplayName("this 调用：@Transactional 失效，那 100.00 落库了")
    void thisInvocationIsNotProxied() {
        ScenarioResult result = runner.record("t", "e", "c", selfInvocationService::thisCall);

        assertThat(thrownException(result)).isEqualTo("BalanceNotEnoughException");
        assertThat(balance(result, "熊大"))
                .as("this 绕过了代理，方法里那笔以自动提交的方式执行完就落库了")
                .isEqualByComparingTo("900.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("改成从容器取代理再调：同一个方法，事务生效了")
    void proxyInvocationWorks() {
        ScenarioResult result = runner.record("t", "e", "c", selfInvocationService::proxyCall);

        assertThat(thrownException(result)).isEqualTo("BalanceNotEnoughException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    // ------------------------------------------------------------ 二、方法可见性

    @Test
    @DisplayName("public 方法：生效")
    void publicMethodIsTransactional() {
        ScenarioResult result = runner.record("t", "e", "c", nonPublicCaller::callPublic);

        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    /**
     * 这条和网上流传的说法相反，是本篇的一个纠正点。
     *
     * <p>{@code @EnableTransactionManagement} 用的是
     * {@code new AnnotationTransactionAttributeSource(false)}（Spring 6.0 起），
     * 非 public 方法一样会被事务拦截器考虑；CGLIB 又能覆写 protected 方法，
     * 两个条件都满足，所以它生效。
     */
    @Test
    @DisplayName("protected 方法：也生效（和「非 public 全失效」的说法相反）")
    void protectedMethodIsTransactional() {
        ScenarioResult result = runner.record("t", "e", "c", nonPublicCaller::callProtected);

        assertThat(balance(result, "熊大"))
                .as("如果这里变成了 900.00，说明框架收紧了 publicMethodsOnly，文章要改")
                .isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("包私有方法：同样生效")
    void packagePrivateMethodIsTransactional() {
        ScenarioResult result = runner.record("t", "e", "c", nonPublicCaller::callPackagePrivate);

        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    /**
     * 这一条很特别：得到的不是「数据不一致」，而是 NPE。
     *
     * <p>因为 CGLIB 覆写不了 final 方法，调用落在代理实例自己身上，
     * 而代理实例是用 Objenesis 造出来的、字段全是 null。
     */
    @Test
    @DisplayName("public final 方法（读实例字段）：抛 NPE，不是静默失效")
    void finalMethodWithInstanceFieldsThrowsNpe() {
        ScenarioResult result = runner.record("t", "e", "c", nonPublicCaller::callFinal);

        assertThat(thrownException(result))
                .as("报错和根因不在一个地方：看起来像依赖注入没生效，实际是 final 挡住了代理")
                .isEqualTo("NullPointerException");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("public final 方法（依赖走参数）：露出真实结果 —— 事务失效，钱留下")
    void finalMethodWithoutInstanceFieldsLosesTransaction() {
        ScenarioResult result = runner.record("t", "e", "c", nonPublicCaller::callFinalWithParams);

        assertThat(thrownException(result)).isEqualTo("IllegalStateException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("static 方法：失效，钱留下")
    void staticMethodLosesTransaction() {
        ScenarioResult result = runner.record("t", "e", "c", nonPublicCaller::callStatic);

        assertThat(thrownException(result)).isEqualTo("IllegalStateException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertTotalUnchanged(result);
    }

    // ------------------------------------------------------------ 三、异常被吞

    @Test
    @DisplayName("异常被 catch 吞掉：没有异常、没有日志，钱没了")
    void swallowedExceptionCommits() {
        ScenarioResult result = runner.record("t", "e", "c", swallowedExceptionService::swallow);

        assertThat(thrownException(result))
                .as("全程没有任何异常 —— 这个坑唯一的症状就是余额不对")
                .isNull();
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("修复一：catch 之后重新抛出，回滚正常")
    void rethrowRollsBack() {
        ScenarioResult result = runner.record("t", "e", "c", swallowedExceptionService::rethrow);

        assertThat(thrownException(result)).isEqualTo("BalanceNotEnoughException");
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    /**
     * 比「忘了抛」更隐蔽：异常确实抛出去了，调用方也收到了，只是类型不对。
     */
    @Test
    @DisplayName("坑：catch 之后包成受检异常再抛 —— 抛了，但等于没抛")
    void rethrowAsCheckedStillCommits() {
        ScenarioResult result = runner.record("t", "e", "c", swallowedExceptionService::rethrowAsChecked);

        assertThat(thrownException(result)).isEqualTo("TransferRejectedException");
        assertThat(balance(result, "熊大"))
                .as("异常类型一换，默认回滚规则就不认了")
                .isEqualByComparingTo("900.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("修复二：吞掉异常但显式标记 rollback-only")
    void markRollbackOnlyWorks() {
        ScenarioResult result = runner.record("t", "e", "c",
                swallowedExceptionService::swallowButMarkRollbackOnly);

        assertThat(thrownException(result))
                .as("方法正常返回和事务回滚并不冲突")
                .isNull();
        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    // ------------------------------------------------------------ 四、换线程

    @Test
    @DisplayName("子线程里写库：子线程不在主线程事务里，它那笔留下了")
    void childThreadWritesOutsideTransaction() throws Exception {
        ScenarioResult result = runner.record("t", "e", "c", childThreadService::writeInChildThread);

        assertThat(thrownException(result)).isEqualTo("BalanceNotEnoughException");
        assertThat(balance(result, "熊大"))
                .as("主线程回滚了")
                .isEqualByComparingTo("1000.00");
        assertThat(balance(result, "熊三"))
                .as("子线程读到「事务=无」，UPDATE 执行完就自动提交了")
                .isEqualByComparingTo("250.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("修复：把 TransactionTemplate 传进子线程，子线程有自己的事务边界")
    void childThreadWithOwnTransaction() throws Exception {
        ScenarioResult result = runner.record("t", "e", "c",
                childThreadService::writeInChildThreadWithOwnTransaction);

        assertThat(balance(result, "熊大")).isEqualByComparingTo("1000.00");
        assertThat(balance(result, "熊三"))
                .as("跨线程没有原子性可言 —— 能修的只是「子线程内部要么全成要么全败」")
                .isEqualByComparingTo("250.00");
        assertTotalUnchanged(result);
    }

    // ------------------------------------------------------------ 五、事务管理器名字

    @Test
    @DisplayName("不写管理器名字：按类型找到唯一的一个，正常提交")
    void anonymousManagerWorks() {
        ScenarioResult result = runner.record("t", "e", "c", managerNameService::anonymous);

        assertThat(thrownException(result)).isNull();
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertTotalUnchanged(result);
    }

    @Test
    @DisplayName("写对管理器名字：正常提交")
    void namedManagerWorks() {
        ScenarioResult result = runner.record("t", "e", "c", managerNameService::named);

        assertThat(thrownException(result)).isNull();
        assertThat(balance(result, "熊大")).isEqualByComparingTo("900.00");
        assertTotalUnchanged(result);
    }

    /**
     * 名字写错的代价：编译通过、启动通过，第一次调用才炸。
     */
    @Test
    @DisplayName("写错管理器名字：调用时抛 NoSuchBeanDefinitionException，方法体一行都没执行")
    void wrongManagerNameFailsAtInvocation() {
        ScenarioResult result = runner.record("t", "e", "c", managerNameService::wrongName);

        assertThat(thrownException(result)).isEqualTo("NoSuchBeanDefinitionException");
        assertThat(balance(result, "熊大"))
                .as("事务管理器在进方法体之前就要确定，所以那笔转账根本没执行")
                .isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }
}
