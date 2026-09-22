package com.xncoding.transaction.service;

import com.xncoding.transaction.exception.BalanceNotEnoughException;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 传播行为的【内层】。单独一个 Bean，这是必须的 ——
 * 同一个类里内部调用不走代理，注解会失效（本篇「四个失效场景」有专门演示）。
 *
 * <p>内层统一操作 3 号（熊三）和 4 号（熊四）账户，外层统一操作 1 号（熊大）和 2 号（熊二）。
 * 这么分工是为了<b>避开行锁</b>：如果把内外层都写成熊大转熊二，
 * {@code REQUIRES_NEW} 那条新连接会去等外层还没提交的行锁，最后以锁等待超时收场，
 * 那就看不到传播行为本身的结果了。
 */
@Service
public class PropagationInnerService {

    private static final BigDecimal AMOUNT = new BigDecimal("50.00");

    private final AccountService accountService;
    private final TxProbe probe;

    public PropagationInnerService(AccountService accountService, TxProbe probe) {
        this.accountService = accountService;
        this.probe = probe;
    }

    /**
     * REQUIRED（默认）：有事务就加入，没有就新开。
     *
     * <p>加入意味着它和外层共用一条连接、一个事务。它抛异常，
     * 会把整个事务标成「只能回滚」，外层就算把异常 catch 掉也没用 ——
     * 提交的时候会抛 {@code UnexpectedRollbackException}。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String requiredFails() {
        accountService.move(3L, 4L, AMOUNT);
        throw new BalanceNotEnoughException("内层 REQUIRED 故意失败");
    }

    /** REQUIRED，但正常结束。用来给上面那一条做对照。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public String requiredSucceeds() {
        accountService.move(3L, 4L, AMOUNT);
        return probe.summary();
    }

    /**
     * REQUIRES_NEW：挂起外层，开一个全新的、独立的事务。
     *
     * <p>关键词是「挂起」。它会去连接池再借一条连接，外层那条连接上绑着的事务
     * 被暂时存到一边，等内层结束后再恢复。所以内层回滚不会影响外层。
     *
     * <p>这个行为的代价要说清楚：多占一条连接。一个方法里循环调用 N 次，
     * 最坏情况下瞬间从池里拿走 N+1 条连接。池子小的时候这就是死锁的来源。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String requiresNewFails() {
        accountService.move(3L, 4L, AMOUNT);
        throw new BalanceNotEnoughException("内层 REQUIRES_NEW 故意失败");
    }

    /**
     * NESTED：在外层事务内部打一个 savepoint，不是真的事务嵌套（JDBC 没有这回事）。
     *
     * <p>失败时回滚到 savepoint，外层的改动还在，外层可以继续提交。
     * 这一点和 REQUIRED 的区别是决定性的：REQUIRED 失败会污染整个事务，
     * NESTED 失败只脏了自己那一小段。
     *
     * <p>它对数据源有要求：事务管理器必须允许嵌套（{@code setNestedTransactionAllowed}）。
     * {@code DataSourceTransactionManager} 的构造里默认就是开的，所以 Boot 自动配置
     * 出来的那个可以直接用；换成手工 new 且没开这个开关的话，
     * 会在运行时抛 {@code NestedTransactionNotSupportedException}。
     */
    @Transactional(propagation = Propagation.NESTED)
    public String nestedFails() {
        accountService.move(3L, 4L, AMOUNT);
        throw new BalanceNotEnoughException("内层 NESTED 故意失败");
    }

    /** NESTED，正常结束。对照用。 */
    @Transactional(propagation = Propagation.NESTED)
    public String nestedSucceeds() {
        accountService.move(3L, 4L, AMOUNT);
        return probe.summary();
    }

    /**
     * NOT_SUPPORTED：把外层事务挂起，自己<b>不在事务里</b>跑。
     *
     * <p>这条语句会立刻自动提交，之后外层无论怎么回滚都带不走它。
     * 这是「我以为它在我的事务里」最典型的翻车方式。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String notSupportedWrites() {
        String state = probe.summary();
        accountService.move(3L, 4L, AMOUNT);
        return state;
    }

    /** NEVER：要求调用方不能有事务。有就抛 IllegalTransactionStateException。 */
    @Transactional(propagation = Propagation.NEVER)
    public String neverWantsNoTransaction() {
        accountService.move(3L, 4L, AMOUNT);
        return probe.summary();
    }

    /** MANDATORY：要求调用方必须有事务。没有就抛 IllegalTransactionStateException。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public String mandatoryNeedsTransaction() {
        accountService.move(3L, 4L, AMOUNT);
        return probe.summary();
    }

    /** SUPPORTS：有就用，没有就不用。它从不主动开事务。 */
    @Transactional(propagation = Propagation.SUPPORTS)
    public String supportsAdapts() {
        String state = probe.summary();
        accountService.move(3L, 4L, AMOUNT);
        return state;
    }
}
