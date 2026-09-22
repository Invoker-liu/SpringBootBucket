package com.xncoding.transaction.event;

import com.xncoding.transaction.domain.TxEvent;
import com.xncoding.transaction.mapper.TxEventMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * 事务事件监听器，四个相位各一个，每个都往 {@code t_tx_event} 写一条。
 *
 * <p>为什么不打日志而落库？因为「监听器到底在事务的哪个时刻被调用」这件事，
 * 日志里要拉时间戳去对，落库更直接：看哪几条记录留下了、哪几条随事务一起消失了，
 * 相位的语义就出来了。特别是 {@code BEFORE_COMMIT} —— 它的写入是<b>跟在事务里</b>的，
 * 事务回滚它也跟着没，日志会骗人（打印出来了），数据库不会。
 *
 * <p>四条 {@code @Transactional} 的传播行为<b>不是随便写的</b>，每一行都有原因：
 *
 * <ul>
 *   <li>{@code BEFORE_COMMIT} 用 {@code REQUIRED}。这个相位还在原事务里，
 *       就是要它加入原事务，才能演示「跟着事务一起回滚」。</li>
 *   <li>{@code AFTER_COMMIT} / {@code AFTER_ROLLBACK} / {@code AFTER_COMPLETION}
 *       用 {@code REQUIRES_NEW}。这三个相位触发时原事务已经结束了，
 *       必须开新事务才写得进去；不写的话，SQL 会在一条已经没人管的连接上执行，
 *       程序不报错、数据也不落库。</li>
 * </ul>
 *
 * <p>而且这四个 {@code @Transactional} 现在是<b>强制要求用对</b>的：
 * Spring Framework <b>6.1 起</b> {@code @EnableTransactionManagement} 注册的监听器工厂
 * 换成了 {@code RestrictedTransactionalEventListenerFactory}（在
 * {@code org.springframework.transaction.annotation} 包下，不在 {@code .event} 包），
 * 它会在启动阶段校验「非 BEFORE_COMMIT 的监听方法如果标了 {@code @Transactional}，
 * 传播行为必须是 REQUIRES_NEW 或 NOT_SUPPORTED」，不满足直接抛
 * {@code IllegalStateException}，应用起不来。
 *
 * <p>这个校验看着严格，其实是在救你：6.1 之前的版本里同样的写法能正常启动，
 * 然后静默丢数据。工程里 {@code TrapListener} 那个类就是这道校验的反面例子，
 * 用 {@code listener-trap} profile 单独启动可以看到它报什么。
 * 拿它当「Framework 7 新特性」是错的 —— 这条限制比 Framework 7 早两个小版本。
 */
@Component
public class TransactionEventRecorder {

    private final TxEventMapper txEventMapper;

    public TransactionEventRecorder(TxEventMapper txEventMapper) {
        this.txEventMapper = txEventMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    @Transactional(propagation = Propagation.REQUIRED)
    public void onBeforeCommit(AccountChangedEvent event) {
        write(event, "BEFORE_COMMIT");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAfterCommit(AccountChangedEvent event) {
        write(event, "AFTER_COMMIT");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAfterRollback(AccountChangedEvent event) {
        write(event, "AFTER_ROLLBACK");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAfterCompletion(AccountChangedEvent event) {
        write(event, "AFTER_COMPLETION");
    }

    /**
     * 没有事务时事件也会被处理。
     *
     * <p>{@code @TransactionalEventListener} 默认只认「有事务」的事件，没事务时直接丢弃。
     * 想让它在无事务环境下也照常执行，得显式打开 {@code fallbackExecution} ——
     * 这个默认值是 {@code false}，踩过一次就忘不了。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAfterCommitWithFallback(AccountChangedEvent event) {
        write(event, "AFTER_COMMIT(无事务时也执行)");
    }

    private void write(AccountChangedEvent event, String phase) {
        TxEvent row = new TxEvent();
        row.setTxName(event.txName());
        row.setPhase(phase);
        row.setDetail("%s %s".formatted(event.action(), event.amount()));
        row.setCreatedAt(LocalDateTime.now());
        txEventMapper.insert(row);
    }
}
