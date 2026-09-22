package com.xncoding.transaction.service;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.exception.BalanceNotEnoughException;
import com.xncoding.transaction.exception.TransferRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

/**
 * 编程式事务：{@code TransactionTemplate}。
 *
 * <p>声明式事务方便，但边界固定在「方法」这一级 —— 你没法让事务从方法中间开始。
 * 真遇到「前两步先读数据、第三步才决定要不要写」这种逻辑，就得用编程式。
 *
 * <p>这个类构造时拿到的 {@code PlatformTransactionManager} 是 Boot 自动配置给的，
 * 顺手也说明了另一件事：容器里只有一个事务管理器时，
 * {@code TransactionTemplate} 这个 bean 由 {@code TransactionTemplateConfiguration}
 * 自动提供，条件是 {@code @ConditionalOnSingleCandidate(PlatformTransactionManager)}。
 * 所以「能直接注入 TransactionTemplate」这件事本身就隐含了「事务管理器唯一」。
 *
 * <p>模板默认的传播行为是 REQUIRED（有就加入、没有就新开），和 {@code @Transactional}
 * 的默认值一致。想换传播行为就自己 new 一个模板并调 setter，业务代码一个字都不用改 ——
 * 见下面 {@code requiresNewTemplate} 的用法。
 *
 * <p>有一点和声明式事务<b>不一样</b>，值得单独记住：编程式事务里受检异常也得回滚。
 * 原因不是 Spring 改了规则，而是 {@code TransactionCallback} 的方法签名里没有
 * {@code throws Exception}，受检异常根本传不出 lambda，你必须把它包成 RuntimeException
 * 才抛得出去 —— 一旦包了就是运行时异常，默认规则自然判它回滚。
 * 「受检异常默认不回滚」这个坑只存在于声明式事务。
 *
 * <p>所有方法都接收 {@code steps} 列表而不是自己造一个返回：这几个方法有一半会以
 * 抛异常收场，抛异常时返回值是拿不到的，步骤存在局部变量里就会跟着异常一起丢。
 */
@Service
public class ProgrammaticService {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    /**
     * 内层用的金额。和外层的 {@link #AMOUNT} 不同，是为了让日志和余额都能一眼分辨
     * 「哪一笔是外层写的、哪一笔是内层写的」。
     */
    private static final BigDecimal INNER_AMOUNT = new BigDecimal("50.00");

    private final AccountService accountService;

    /** Boot 自动配置给的那一个，传播行为 REQUIRED。 */
    private final TransactionTemplate template;

    /** 手工造的，只改了传播行为。编程式事务换传播行为就是改这个对象。 */
    private final TransactionTemplate requiresNewTemplate;

    public ProgrammaticService(AccountService accountService, PlatformTransactionManager transactionManager) {
        this.accountService = accountService;
        this.template = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private void step(List<StepResult> steps, String label, String detail) {
        steps.add(accountService.step(label, detail));
    }

    /** 正常走完，提交。 */
    public void templateCommit(List<StepResult> steps) {
        template.executeWithoutResult(status -> {
            accountService.move(1L, 2L, AMOUNT);
            step(steps, "模板里转了 100.00", "status.isNewTransaction() = " + status.isNewTransaction());
        });
        step(steps, "execute 返回", "事务已提交");
    }

    /**
     * 用 {@code setRollbackOnly()} 回滚，方法本身正常返回。
     *
     * <p>这是声明式事务做不到的事：{@code @Transactional} 只有一个出口 —— 抛异常。
     * 你没法让方法正常返回、同时告诉事务「回滚吧」。编程式可以直接改状态，
     * 不需要靠异常来传达回滚意图。
     */
    public void templateRollbackByFlag(List<StepResult> steps) {
        template.executeWithoutResult(status -> {
            accountService.move(1L, 2L, AMOUNT);
            step(steps, "模板里转了 100.00", "接下来只打标记，不抛异常");
            status.setRollbackOnly();
            step(steps, "status.setRollbackOnly()", "方法会正常返回，但事务要回滚");
        });
        step(steps, "execute 返回", "没有抛异常，事务仍然回滚了");
    }

    /** 抛运行时异常：和声明式一样，回滚。 */
    public void templateRollbackByException(List<StepResult> steps) {
        template.executeWithoutResult(status -> {
            accountService.move(1L, 2L, AMOUNT);
            step(steps, "模板里转了 100.00", "接下来抛运行时异常");
            throw new BalanceNotEnoughException("模板里故意抛运行时异常");
        });
    }

    /**
     * 受检异常必须先包成运行时异常才抛得出去 —— 这不是风格问题，是编译期强制的。
     *
     * <p>{@code TransactionCallback} 没有声明受检异常，所以下面那句直接写会编译不过，
     * 必须包一层。包完之后它就是 RuntimeException 了，回滚。
     *
     * <p>把它和 {@code RollbackService#checkedExceptionCommits()} 放一起看更清楚：
     * 同一个受检异常，走声明式会提交、走编程式会回滚。差别不在于事务规则，
     * 而在于编程式压根不允许它原样穿出去。
     */
    public void templateRollbackByWrappedChecked(List<StepResult> steps) {
        template.executeWithoutResult(status -> {
            accountService.move(1L, 2L, AMOUNT);
            step(steps, "模板里转了 100.00", "接下来抛受检异常，但必须包一层才编译得过");
            try {
                throw new TransferRejectedException("受检异常，被包了一层才抛得出去");
            } catch (TransferRejectedException e) {
                throw new IllegalStateException("包装后的受检异常", e);
            }
        });
    }

    /** 匿名内部类写法。和 lambda 是同一个东西，写全一点方便对照签名。 */
    public void templateAnonymousClass(List<StepResult> steps) {
        template.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(
                    org.springframework.transaction.TransactionStatus status) {
                accountService.move(1L, 2L, AMOUNT);
                step(steps, "匿名内部类写法", "status.isNewTransaction() = " + status.isNewTransaction());
            }
        });
    }

    /**
     * 在外层事务里用 REQUIRES_NEW 模板，得到一个独立事务。
     *
     * <p>这是编程式事务能覆盖的真实场景：主流程在一个大事务里，但审计日志、风控记录
     * 这类数据必须<b>立刻落库</b>，不能被主流程的回滚带走。用声明式得再抽一个 Bean 出来
     * 写 {@code @Transactional(REQUIRES_NEW)}，编程式就是换一个模板对象。
     */
    @Transactional
    public void requiresNewInsideTransaction(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层事务里转了 100.00", "在外层事务里");
        requiresNewTemplate.executeWithoutResult(status -> {
            accountService.move(3L, 4L, INNER_AMOUNT);
            step(steps, "内层模板接手，转出 50.00（账户不同，避免抢锁）",
                    "status.isNewTransaction() = " + status.isNewTransaction());
        });
        step(steps, "内层已提交，接下来外层要失败", "外层那 100.00 会回滚，内层那 50.00 留下");
        throw new BalanceNotEnoughException("外层故意失败");
    }
}
