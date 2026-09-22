package com.xncoding.transaction.failure;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.exception.BalanceNotEnoughException;
import com.xncoding.transaction.service.AccountService;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 失效场景一：同类内部调用。
 *
 * <p>这是所有失效场景里出现频率最高的一个，原因并不神秘：
 * Spring 的事务靠<b>代理</b>实现。外部调用先到代理对象，代理在方法前后开事务、提交；
 * 而 {@code this.xxx()} 这句代码里的 {@code this} 是原始对象，不是代理，
 * 方法调用直接在对象内部发生，事务拦截器根本没机会介入。
 *
 * <p>判断标准一句话：<b>这次调用有没有经过那个被注入到别处的对象。</b>
 *
 * <p>{@link #thisCall} 和 {@link #proxyCall} 做的是完全一样的业务动作，
 * 唯一差别是「谁来调 {@link #chargeAndFail}」，结果一个扣钱、一个不扣。
 *
 * <p>{@link #chargeAndFail} 里是先写库再抛异常，写这一笔本身不会失败 ——
 * 它留不留下，只取决于当时它在不在事务里。
 */
@Service
public class SelfInvocationService {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private final AccountService accountService;
    private final TxProbe probe;

    /**
     * 注入上下文是为了能「从容器里再取一次自己」，从而拿到代理对象。
     *
     * <p>真实项目里更常见的写法是拆成两个 Bean（就像本工程
     * {@code PropagationOuterService} 调用 {@code PropagationInnerService} 那样），
     * 或者用 {@code @Lazy} 注入自身。这里用 {@code getBean} 是因为它把
     * 「我拿到的到底是不是代理」写得最直白，读者不用再想一层。
     */
    private final ApplicationContext context;

    public SelfInvocationService(AccountService accountService, TxProbe probe, ApplicationContext context) {
        this.accountService = accountService;
        this.probe = probe;
        this.context = context;
    }

    /** 错误示范：{@code this} 直接调用，{@link #chargeAndFail} 上的 {@code @Transactional} 完全不起作用。 */
    public void thisCall(List<StepResult> steps) {
        steps.add(new StepResult("外层方法没有 @Transactional", probe.summary(),
                "现在整条调用链上没有任何事务"));
        steps.add(new StepResult("准备用 this 调用本类的另一个方法", probe.summary(),
                "那个方法上的 @Transactional 会失效"));
        this.chargeAndFail();
    }

    /** 正确示范：从容器里取出代理对象再调，注解生效。 */
    public void proxyCall(List<StepResult> steps) {
        steps.add(new StepResult("外层方法没有 @Transactional", probe.summary(),
                "但下面这次调用会经过事务代理"));
        SelfInvocationService proxy = context.getBean(SelfInvocationService.class);
        steps.add(new StepResult("从容器里取出的对象是不是代理", probe.summary(),
                "实际类型 = " + proxy.getClass().getName()));
        proxy.chargeAndFail();
    }

    /**
     * 被调用的那个方法。它自己没问题，问题永远出在「谁调它」上。
     *
     * <p>先转 100.00 再抛异常：有事务就回滚（余额不变），没事务就立即提交（余额少 100）。
     */
    @Transactional
    public void chargeAndFail() {
        accountService.move(1L, 2L, AMOUNT);
        throw new BalanceNotEnoughException("内层故意失败，看这一笔留没留下");
    }
}
