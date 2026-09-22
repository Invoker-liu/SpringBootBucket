package com.xncoding.transaction.failure;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.service.AccountService;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link NonPublicService} 的调用方。
 *
 * <p>为什么需要这么一个类？因为 {@code protected} 和包私有方法只能从<b>同一个包</b>里调用，
 * 而 Controller 在 {@code com.xncoding.transaction.controller} 包下，够不着它们。
 *
 * <p>这里注入的 {@code target} 是容器给的 <b>代理对象</b>，所以下面每一次调用
 * 都会经过事务拦截器 —— 这也正是实验能成立的前提。如果换成在
 * {@code NonPublicService} 内部用 {@code this} 调，测出来的就变成
 * 「同类内部调用」那个场景了，两个坑会缠在一起，谁都说不清。
 */
@Service
public class NonPublicCaller {

    private final NonPublicService target;
    private final AccountService accountService;
    private final TxProbe probe;

    public NonPublicCaller(NonPublicService target, AccountService accountService, TxProbe probe) {
        this.target = target;
        this.accountService = accountService;
        this.probe = probe;
    }

    public void callPublic(List<StepResult> steps) {
        target.publicMethod(steps);
    }

    public void callProtected(List<StepResult> steps) {
        target.protectedMethod(steps);
    }

    public void callPackagePrivate(List<StepResult> steps) {
        target.packagePrivateMethod(steps);
    }

    public void callFinal(List<StepResult> steps) {
        target.finalMethod(steps);
    }

    public void callFinalWithParams(List<StepResult> steps) {
        target.finalMethodWithParams(steps, accountService, probe);
    }

    public void callStatic(List<StepResult> steps) {
        NonPublicService.staticMethod(steps, accountService, probe);
    }
}
