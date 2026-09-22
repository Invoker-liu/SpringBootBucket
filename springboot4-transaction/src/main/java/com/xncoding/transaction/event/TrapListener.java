package com.xncoding.transaction.event;

import com.xncoding.transaction.domain.TxEvent;
import com.xncoding.transaction.mapper.TxEventMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * 故意写错的事务事件监听器 —— 反面教材，只在 {@code listener-trap} profile 下加载。
 *
 * <p>错在哪：{@code AFTER_COMMIT} 相位配了一个没写传播行为的 {@code @Transactional}，
 * 也就是默认的 {@code REQUIRED}。看起来挺合理（"提交之后写条记录，开个事务不过分吧"），
 * 实际的处境是：这个相位被调用时原事务已经提交完毕，线程上绑着的那条连接
 * 已经处在「事务结束了但还没还给连接池」的尴尬状态，REQUIRED 会当成「加入已有事务」
 * 来处理，于是 SQL 真的执行了、却没人在最后提交它。程序一声不吭，数据凭空消失。
 *
 * <p>Spring Framework <b>6.1 起</b>把这种写法从「静默丢数据」改成了「启动就报错」：
 * {@code @EnableTransactionManagement} 注册的监听器工厂换成了
 * {@code RestrictedTransactionalEventListenerFactory}（在
 * {@code org.springframework.transaction.annotation} 包下），它在启动阶段会检查每一个
 * {@code @TransactionalEventListener} 方法，只要相位不是 {@code BEFORE_COMMIT}
 * 又标了 {@code @Transactional}、且传播行为不是 {@code REQUIRES_NEW} 或
 * {@code NOT_SUPPORTED}，就直接抛 {@code IllegalStateException} 让应用起不来。
 *
 * <p>这个类留着不删，是为了能亲手复现一次报错。默认 profile 下它不加载，
 * 不影响主流程：
 *
 * <pre>
 * SERVER_PORT=8081 .workbuddy/tools/mvn.sh -q spring-boot:run \
 *     -Dspring-boot.run.arguments=--spring.profiles.active=listener-trap
 * </pre>
 *
 * <p>报错会包在 {@code BeanInitializationException} 里，cause 才是真正的那句话，
 * 正文里贴的是 cause 的原文。
 */
@Component
@Profile("listener-trap")
public class TrapListener {

    private final TxEventMapper txEventMapper;

    public TrapListener(TxEventMapper txEventMapper) {
        this.txEventMapper = txEventMapper;
    }

    /** 这一行就是错误本身。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void onAfterCommit(AccountChangedEvent event) {
        TxEvent row = new TxEvent();
        row.setTxName(event.txName());
        row.setPhase("TRAP");
        row.setDetail("这个类不该被加载");
        row.setCreatedAt(LocalDateTime.now());
        txEventMapper.insert(row);
    }
}
