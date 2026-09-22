package com.xncoding.transaction.support;

import com.xncoding.transaction.dto.TxState;
import com.xncoding.transaction.mapper.AccountMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务状态探针。
 *
 * <p>这个类是整篇的「取证工具」。{@code @Transactional} 不生效的时候程序不会报错、
 * 不会告警，唯一的区别是「方法里到底有没有事务」。所以判断它有没有生效，
 * 只能靠主动去读事务的运行时状态。
 *
 * <p>三个数据源各有侧重：
 * <ul>
 *   <li>{@link TransactionSynchronizationManager} —— 当前线程绑没绑事务、事务名、只读标记、隔离级别。
 *       这些是纯内存状态，不碰数据库。</li>
 *   <li>{@code CONNECTION_ID()} 与 {@code DATABASE()} —— 真去数据库问。注意它查的是
 *       「当前线程绑定的那条连接」，所以事务进行中问出来的就是事务自己的连接。</li>
 *   <li>{@link TransactionAspectSupport#currentTransactionStatus()} —— 只有它能拿到
 *       savepoint 这种「事务内部结构」的信息，NESTED 那一节全靠它。</li>
 * </ul>
 *
 * <p>写成 Bean 而不是静态工具类，是为了让它的调用真正跨一次 bean 边界，
 * 顺带避开同类内部调用那个坑（本篇专门有一节讲这个坑）。
 */
@Component
public class TxProbe {

    private final AccountMapper accountMapper;

    public TxProbe(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    public TxState snapshot() {
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        String name = TransactionSynchronizationManager.getCurrentTransactionName();
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        String database = accountMapper.currentDatabase();
        Long connectionId = accountMapper.currentConnectionId();
        return new TxState(active, name, readOnly, isolation, database, connectionId,
                hasSavepoint(), isNewTransaction(), isRollbackOnly());
    }

    /** 简短的一行，用来塞进步骤说明里。 */
    public String summary() {
        TxState s = snapshot();
        return "事务=%s, 事务名=%s, 只读=%s, 隔离级别=%s, 连接=%s, savepoint=%s".formatted(
                s.active() ? "有" : "无",
                s.name() == null ? "null" : s.name(),
                s.readOnly(),
                s.isolation() == null ? "跟随数据库" : s.isolation(),
                s.connectionId(),
                s.hasSavepoint());
    }

    /**
     * 隔离级别的对照读数。
     *
     * <p>左边是 Spring 在 {@code TransactionSynchronizationManager} 里记下的值，
     * 右边是数据库会话里真正的值。{@code Isolation.DEFAULT} 会让左边读成
     * 「未声明」而右边保持数据库默认 —— 这正是「你以为声明了隔离级别、其实什么都没发生」
     * 的证据。
     *
     * <p>还有一个容易漏的前提：Spring 只在<b>新事务</b>开始的时候才把隔离级别写进连接。
     * 如果当前方法是加进外层已有事务的（传播行为不是 REQUIRES_NEW / NESTED），
     * 这里声明的隔离级别会被<b>静默忽略</b>。
     */
    public String isolationSummary() {
        Integer declared = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        String database = accountMapper.currentIsolationLevel();
        return "Spring 声明=%s, 数据库会话=%s".formatted(isolationName(declared), database);
    }

    /**
     * 只读标记的对照读数。
     *
     * <p>同样是一边读 Spring 的记录、一边读数据库的会话变量。
     * 两个值对不上的时候，就说明 {@code readOnly = true} 只写在了 Java 对象上，
     * 没有真的落到底层连接。
     */
    public String readOnlySummary() {
        boolean declared = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        int database = accountMapper.sessionTransactionReadOnly();
        return "Spring 声明=%s, 数据库会话=%s".formatted(declared, database == 1 ? "只读" : "可读写");
    }

    private static String isolationName(Integer level) {
        if (level == null) {
            return "null（ISOLATION_DEFAULT，不改连接）";
        }
        return switch (level) {
            case 0 -> "TRANSACTION_NONE";
            case 1 -> "READ_UNCOMMITTED";
            case 2 -> "READ_COMMITTED";
            case 4 -> "REPEATABLE_READ";
            case 8 -> "SERIALIZABLE";
            default -> String.valueOf(level);
        };
    }

    /**
     * 有没有 savepoint。NESTED 的传播行为在 JDBC 上是靠 savepoint 实现的，
     * 所以这一条是「NESTED 真的生效了」的直接证据。
     *
     * <p>没有事务的时候 {@code currentTransactionStatus()} 会抛
     * {@link NoTransactionException}，这里吞掉当 false 处理 —— 探针本身不该把业务搞崩。
     */
    public static boolean hasSavepoint() {
        try {
            return TransactionAspectSupport.currentTransactionStatus().hasSavepoint();
        } catch (NoTransactionException e) {
            return false;
        }
    }

    public static boolean isNewTransaction() {
        try {
            return TransactionAspectSupport.currentTransactionStatus().isNewTransaction();
        } catch (NoTransactionException e) {
            return false;
        }
    }

    public static boolean isRollbackOnly() {
        try {
            return TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();
        } catch (NoTransactionException e) {
            return false;
        }
    }
}
