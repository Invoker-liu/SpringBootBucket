package com.xncoding.transaction.service;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.exception.BalanceNotEnoughException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 传播行为的【外层】。每个方法演示一对「外层动作 + 内层调用」的组合。
 *
 * <p>外层统一操作 1 号（熊大）和 2 号（熊二），内层统一操作 3 号（熊三）和 4 号（熊四）。
 * 这么分工是为了<b>避开行锁</b>：如果把内外层都写成熊大转熊二，
 * {@code REQUIRES_NEW} 那条新连接会去等外层还没提交的行锁，最后以锁等待超时收场，
 * 那就看不到传播行为本身的结果了，看到的是 InnoDB 的锁行为。
 *
 * <p>方法都接收一个 {@code steps} 列表而不是自己造一个返回，原因有两个：
 * <ul>
 *   <li>这些方法有一半会以抛异常收场，而抛异常时返回值是拿不到的 ——
 *       如果步骤存在局部变量里，前面记录的几步就跟着异常一起丢了，
 *       恰恰是它们最能说明问题（比如「异常被 catch 住了」那一步）。</li>
 *   <li>最终余额必须在事务之外读。这些方法只记过程，不读结果，
 *       结果由 Controller 在事务结束后统一读，避免「在事务里读自己没提交的写入」这种自证。</li>
 * </ul>
 */
@Service
public class PropagationOuterService {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private final AccountService accountService;
    private final PropagationInnerService inner;

    public PropagationOuterService(AccountService accountService, PropagationInnerService inner) {
        this.accountService = accountService;
        this.inner = inner;
    }

    private void step(List<StepResult> steps, String label, String detail) {
        steps.add(accountService.step(label, detail));
    }

    /**
     * 外层 REQUIRED + 内层 REQUIRED 抛异常 + 外层把异常 catch 掉。
     *
     * <p>直觉是「我 catch 了，所以没事」。实际结果是外层<b>提交失败</b>，
     * 抛 {@code UnexpectedRollbackException}。因为内外层其实是一条事务，
     * 内层失败时事务被标成 rollback-only，这个标记不是异常、catch 不掉。
     * 到提交的那一刻事务管理器才发现「你早说不要提交啊」，于是抛异常告诉你。
     *
     * <p>这个异常的名字起得很准：它表示「你没打算回滚，但事务已经只能回滚了」。
     */
    @Transactional
    public void requiredInnerFailsCaught(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层写入 熊大 -> 熊二 100.00", "在外层事务里");
        try {
            String state = inner.requiredFails();
            step(steps, "内层 REQUIRED（不该走到这）", state);
        } catch (BalanceNotEnoughException e) {
            step(steps, "内层 REQUIRED 抛异常，外层 catch 住了",
                    "异常类型=" + e.getClass().getSimpleName() + "，但事务已经被标记 rollback-only");
        }
        step(steps, "外层准备提交", "前面那句 catch 有没有用，就看这一步");
        // 走到这里就会抛 UnexpectedRollbackException
    }

    /** 同一件事，但外层不 catch。异常直接穿出，事务回滚。 */
    @Transactional
    public void requiredInnerFailsNotCaught(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层写入 熊大 -> 熊二 100.00", "在外层事务里");
        step(steps, "内层 REQUIRED 即将失败", "这次不 catch，异常会穿出外层");
        inner.requiredFails();
    }

    /** 对照组：内外层都成功，一起提交。 */
    @Transactional
    public void requiredInnerSucceeds(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层写入 熊大 -> 熊二 100.00", "在外层事务里");
        String state = inner.requiredSucceeds();
        step(steps, "内层 REQUIRED 成功", "内外层是同一条事务，内层状态：" + state);
    }

    /**
     * 内层 REQUIRES_NEW 失败 + 外层 catch 掉 → 外层正常提交。
     *
     * <p>和「内层 REQUIRED 失败」那一对比着看最清楚：两次都 catch 了，
     * 一次外层能提交，一次提交不了。差别只在「内层是不是同一条事务」。
     */
    @Transactional
    public void requiresNewInnerFailsCaught(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层写入 熊大 -> 熊二 100.00", "在外层事务里");
        try {
            inner.requiresNewFails();
        } catch (BalanceNotEnoughException e) {
            step(steps, "内层 REQUIRES_NEW 抛异常，外层 catch 住了",
                    "内层是独立事务，它的失败不影响外层");
        }
        step(steps, "外层准备提交", "这次应该能提交成功");
    }

    /**
     * 内层 NESTED 失败 + 外层 catch 掉 → 外层提交，内层的改动退回到 savepoint。
     *
     * <p>表面效果和 REQUIRES_NEW 那一对一样（外层都保住了），机制完全不同：
     * NESTED 全程一条连接、一个事务，靠 savepoint 做局部回退；
     * REQUIRES_NEW 是两条连接、两个事务。日志里的 {@code Switching JDBC Connection}
     * 只在 REQUIRES_NEW 出现过。
     */
    @Transactional
    public void nestedInnerFailsCaught(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层写入 熊大 -> 熊二 100.00", "在外层事务里");
        try {
            inner.nestedFails();
        } catch (BalanceNotEnoughException e) {
            step(steps, "内层 NESTED 抛异常，外层 catch 住了",
                    "回滚到 savepoint，外层不受影响");
        }
        step(steps, "外层准备提交", "这次应该能提交成功");
    }

    /** NESTED 成功的那一半对照。 */
    @Transactional
    public void nestedInnerSucceeds(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层写入 熊大 -> 熊二 100.00", "在外层事务里");
        String state = inner.nestedSucceeds();
        step(steps, "内层 NESTED 成功", "内层状态：" + state);
    }

    /**
     * 内层 NOT_SUPPORTED 写完，外层再抛异常。
     *
     * <p>结果最容易让人懵：外层的 100.00 回滚了，内层的 50.00 留下了，
     * 而这两笔操作写在同一个方法调用链里，看起来属于同一次业务动作。
     */
    @Transactional
    public void notSupportedInnerCommitsIndependently(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层写入 熊大 -> 熊二 100.00", "在外层事务里");
        String state = inner.notSupportedWrites();
        step(steps, "内层 NOT_SUPPORTED 写入 熊三 -> 熊四 50.00",
                "内层事务状态：" + state + "，这条 UPDATE 已经自动提交了");
        step(steps, "外层即将抛异常", "外层那 100.00 会回滚，内层那 50.00 不会");
        throw new BalanceNotEnoughException("外层故意失败，看内层那笔留没留下");
    }

    /** 没有事务的情况下调用 MANDATORY → 抛 IllegalTransactionStateException。 */
    public void mandatoryWithoutTransaction(List<StepResult> steps) {
        step(steps, "外层方法没有 @Transactional", "当前没有事务，内层要求必须有");
        inner.mandatoryNeedsTransaction();
    }

    /** 有事务的情况下调用 NEVER → 抛 IllegalTransactionStateException。 */
    @Transactional
    public void neverInsideTransaction(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层写入 熊大 -> 熊二 100.00", "在外层事务里，内层要求不能有事务");
        inner.neverWantsNoTransaction();
    }

    /** 没有事务时调 SUPPORTS：不开事务，直接跑。 */
    public void supportsOutsideTransaction(List<StepResult> steps) {
        String state = inner.supportsAdapts();
        step(steps, "没有事务时调用 SUPPORTS", "内层事务状态：" + state);
    }

    /** 有事务时调 SUPPORTS：加入现有事务。 */
    @Transactional
    public void supportsInsideTransaction(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "外层写入 熊大 -> 熊二 100.00", "在外层事务里");
        String state = inner.supportsAdapts();
        step(steps, "有事务时调用 SUPPORTS", "内层事务状态：" + state);
    }
}
