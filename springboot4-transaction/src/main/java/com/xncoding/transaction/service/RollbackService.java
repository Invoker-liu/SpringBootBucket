package com.xncoding.transaction.service;

import com.xncoding.transaction.exception.BalanceNotEnoughException;
import com.xncoding.transaction.exception.TransferRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 回滚规则。本篇第一个必须纠正的认知就在这里。
 *
 * <p>{@code @Transactional} 的 {@code rollbackFor} 默认值是<b>空数组</b>，
 * 不是「Throwable 及其子类」。真正决定回不回滚的是
 * {@code DefaultTransactionAttribute#rollbackOn(Throwable)}，它的实现只有一句话：
 *
 * <pre>
 * return (ex instanceof RuntimeException || ex instanceof Error);
 * </pre>
 *
 * <p>也就是说：<b>受检异常抛出去，事务照样提交</b>。异常会正常抛给调用方，
 * 调用方以为失败了，数据却已经落库了 —— 这是本篇最值钱的一个坑，因为它的表现
 * 和「成功」几乎一样，只有查库才看得出来。
 */
@Service
public class RollbackService {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private final AccountService accountService;

    public RollbackService(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 对照组：不抛异常，正常提交。它证明「事务本身是工作的」，不是环境问题。 */
    @Transactional
    public void plainCommit() {
        accountService.move(1L, 2L, AMOUNT);
    }

    /** 运行时异常：默认就回滚。 */
    @Transactional
    public void runtimeExceptionRollsBack() {
        accountService.move(1L, 2L, AMOUNT);
        throw new BalanceNotEnoughException("故意抛运行时异常，看它回不回滚");
    }

    /**
     * 受检异常：默认<b>不回滚</b>。
     *
     * <p>方法里连 rollbackFor 都没写，就是最朴素的 {@code @Transactional}。
     * 结果是异常抛出去了，钱也扣了。
     */
    @Transactional
    public void checkedExceptionCommits() throws TransferRejectedException {
        accountService.move(1L, 2L, AMOUNT);
        throw new TransferRejectedException("故意抛受检异常，看它回不回滚");
    }

    /**
     * 显式声明 {@code rollbackFor} 之后，受检异常才回滚。
     *
     * <p>和上一个方法逐字对照，唯一差别就是多了 {@code rollbackFor}。
     * 实际项目里的规矩是：只要方法签名上会出现 {@code throws}，
     * {@code rollbackFor} 就必须写。
     */
    @Transactional(rollbackFor = TransferRejectedException.class)
    public void checkedExceptionRollsBackWithRollbackFor() throws TransferRejectedException {
        accountService.move(1L, 2L, AMOUNT);
        throw new TransferRejectedException("声明了 rollbackFor，这次应该回滚");
    }

    /**
     * 反过来：{@code noRollbackFor} 让一个默认会回滚的运行时异常变得不回滚。
     *
     * <p>这个用法看着别扭，但真实场景是有的：某个异常表示「业务上确实失败了，
     * 但失败记录本身要留下」。注意它是「异常照抛、数据照留」，
     * 和 {@code rollbackFor} 一样只是改回滚判定，不改变异常的传播。
     */
    @Transactional(noRollbackFor = BalanceNotEnoughException.class)
    public void runtimeExceptionSurvivesWithNoRollbackFor() {
        accountService.move(1L, 2L, AMOUNT);
        throw new BalanceNotEnoughException("声明了 noRollbackFor，这次不该回滚");
    }

    /**
     * {@code rollbackFor = Exception.class}：一刀切让受检异常也回滚。
     *
     * <p>这是很多人绕开默认规则的首选写法。它只覆盖当前方法，
     * 想全局改掉得用 {@code @EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)}
     * —— 那个开关是 Spring Framework 7 新加的，正文里有单独一节。
     *
     * <p>写法上有个细节：{@code rollbackFor} 的类型是
     * {@code Class<? extends Throwable>[]}，所以要写 {@code {Exception.class}}。
     */
    @Transactional(rollbackFor = {Exception.class})
    public void exceptionClassRollsBackEverything() throws TransferRejectedException {
        accountService.move(1L, 2L, AMOUNT);
        throw new TransferRejectedException("rollbackFor = Exception.class，受检异常也回滚");
    }
}
