package com.xncoding.transaction.support;

import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.service.AccountService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景执行器。所有演示接口都从它这里走，负责三件事，顺序不能换：
 *
 * <ol>
 *   <li><b>先重置</b> —— 把四个账户刷回固定初值。不重置的话，同一个场景连调两次
 *       看到的是两次叠加的结果，没法跟文章里的数字对照。</li>
 *   <li><b>再执行业务</b> —— 交给各个 Service。这一步可能正常返回，也可能抛异常。</li>
 *   <li><b>最后读余额</b> —— 这一步是整篇最容易被写错的地方：读余额必须发生在
 *       <b>事务之外</b>。如果在事务里读，读到的是本事务尚未提交的中间状态，
 *       拿它当「提交了没有」的证据就是自己骗自己 —— 未提交的事务当然读得到自己写的值。</li>
 * </ol>
 *
 * <p>异常统一由这里接住，转成一条步骤记录，而不是让它变成 500 响应。
 * 因为本篇有大量「故意抛异常」的场景，异常类型本身就是实验数据的一部分，
 * 得跟余额一起返回才看得懂。真正需要走 RFC 9457 的是别的地方
 * （比如参数写错、余额不足这类接口层面的错误）。
 */
@Component
public class ScenarioRunner {

    private final AccountService accountService;

    public ScenarioRunner(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * 场景主体。用 {@code List} 做参数而不是返回值，是为了支持「执行到一半抛异常」
     * 这种情况 —— 前面几步的记录已经写进 list 了，异常不会把它们一起丢掉。
     *
     * <p>声明 {@code throws Exception} 是必须的：本篇有一半的场景就是靠抛受检异常
     * 来演示回滚规则的，用 JDK 自带的 {@code Consumer} 那种不声明受检异常的函数式接口
     * 会编译不过。
     */
    @FunctionalInterface
    public interface ScenarioBody {
        void run(List<StepResult> steps) throws Exception;
    }

    public ScenarioResult record(String scenario, String expectation, String conclusion, ScenarioBody body) {
        accountService.reset();
        List<StepResult> steps = new ArrayList<>();
        try {
            body.run(steps);
        } catch (Throwable e) {
            steps.add(new StepResult(
                    "调用抛出 " + e.getClass().getSimpleName(),
                    TransactionSynchronizationManager.isActualTransactionActive()
                            ? "还在一层事务里" : "已出事务边界，事务已经处理完了",
                    "message = " + e.getMessage()));
        }
        return accountService.build(scenario, expectation, steps, conclusion);
    }
}
