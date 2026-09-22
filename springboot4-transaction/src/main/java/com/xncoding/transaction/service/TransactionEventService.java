package com.xncoding.transaction.service;

import com.xncoding.transaction.domain.TxEvent;
import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.event.AccountChangedEvent;
import com.xncoding.transaction.exception.BalanceNotEnoughException;
import com.xncoding.transaction.mapper.TxEventMapper;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;

/**
 * 发布事务事件。
 *
 * <p>事件本身是 Spring 早就有的能力（{@code ApplicationEventPublisher}），
 * 事务事件在它之上加了一层约束：<b>监听器的执行被绑定到事务的生命周期上</b>。
 * 想让「订单创建成功之后再发券」这类副作用不要在主流程还没提交时就执行，
 * 用它最省事 —— 不需要引入消息队列，也不用自己写回调。
 *
 * <p>但省事是有代价的，三个方法刚好覆盖三种情况：
 *
 * <ul>
 *   <li>{@link #publishAndCommit} —— 事务提交，该触发的相位都触发。</li>
 *   <li>{@link #publishAndRollback} —— 事务回滚，{@code BEFORE_COMMIT} 里写的那条
 *       <b>会跟着一起消失</b>，尽管监听器确实执行过、SQL 也确实发出去过。
 *       这一条是整节的核心：在 {@code BEFORE_COMMIT} 里做有副作用的操作
 *       （发短信、调第三方接口），主事务一回滚，副作用就变成「有去无回」——
 *       外部系统不会跟着回滚。</li>
 *   <li>{@link #publishWithoutTransaction} —— 没有事务时发布事件，
 *       只有打开了 {@code fallbackExecution} 的那个监听器会响应。</li>
 * </ul>
 *
 * <p>把事件写进数据库表而不是打日志，是因为日志里的事件顺序得靠时间戳去对，
 * 而落库的先后天然可信。更重要的是：随事务回滚掉的那条在日志里还在、在库里没了，
 * 这个「对不上」本身就是结论。
 */
@Service
public class TransactionEventService {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private final AccountService accountService;
    private final ApplicationEventPublisher publisher;
    private final TxEventMapper txEventMapper;
    private final TxProbe probe;

    public TransactionEventService(AccountService accountService,
                                   ApplicationEventPublisher publisher,
                                   TxEventMapper txEventMapper,
                                   TxProbe probe) {
        this.accountService = accountService;
        this.publisher = publisher;
        this.txEventMapper = txEventMapper;
        this.probe = probe;
    }

    private void step(List<StepResult> steps, String label, String detail) {
        steps.add(accountService.step(label, detail));
    }

    /** 事务提交。 */
    @Transactional
    public void publishAndCommit(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "事务里转了 100.00", "接下来发布事件");
        publish(steps, "转账成功");
        step(steps, "事件已发布，事务即将提交", "监听器写的事件记录在提交阶段才会出现");
    }

    /** 事务回滚。 */
    @Transactional
    public void publishAndRollback(List<StepResult> steps) {
        accountService.move(1L, 2L, AMOUNT);
        step(steps, "事务里转了 100.00", "接下来发布事件");
        publish(steps, "转账成功");
        step(steps, "事务即将回滚", "看 BEFORE_COMMIT 那条记录会不会也没了");
        throw new BalanceNotEnoughException("故意失败，让这个事务回滚");
    }

    /**
     * 没有事务时发布事件。
     *
     * <p>除了那个开了 {@code fallbackExecution} 的监听器，其余几个都不会被触发 ——
     * 这正是 {@code @TransactionalEventListener} 和普通 {@code @EventListener} 的分界：
     * 前者默认只在事务里工作，没事务就当没听见。
     */
    public void publishWithoutTransaction(List<StepResult> steps) {
        publish(steps, "无事务环境下发布的事件");
        step(steps, "事件已发布", "当前没有事务，只有开了 fallbackExecution 的监听器会响应");
    }

    private void publish(List<StepResult> steps, String action) {
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        if (txName == null) {
            txName = "(无事务)";
        }
        publisher.publishEvent(new AccountChangedEvent(action, AMOUNT, txName));
        step(steps, "publishEvent 已返回",
                "事务名 = " + txName + "；此时事件表里还是空的（提交阶段才会写）");
    }

    public List<TxEvent> events() {
        return txEventMapper.findAll();
    }

    public long clearEvents() {
        return txEventMapper.deleteAll();
    }
}
