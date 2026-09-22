package com.xncoding.transaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xncoding.transaction.domain.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账户 Mapper。
 *
 * <p>全部用注解写 SQL，一个 XML 都没有。本篇的主角是事务不是 ORM，
 * 让读者在看事务的时候还要跳到 XML 里对字段名，是没必要的负担。
 */
@Mapper
public interface AccountMapper extends BaseMapper<Account> {

    @Select("SELECT id, owner, balance, version, updated_at FROM t_account ORDER BY id")
    List<Account> findAll();

    @Select("SELECT id, owner, balance, version, updated_at FROM t_account WHERE owner = #{owner}")
    Account findByOwner(@Param("owner") String owner);

    /**
     * 原子加减余额。写成 {@code balance = balance + #{delta}} 而不是
     * 「先 select 出来、在 Java 里算好、再 update 回去」，是为了不给本篇引入额外的并发变量：
     * 这样余额怎么变只取决于事务提交没提交，跟别的东西无关。
     */
    @Update("UPDATE t_account SET balance = balance + #{delta}, version = version + 1, updated_at = NOW(3) " +
            "WHERE id = #{id}")
    int addBalance(@Param("id") Long id, @Param("delta") BigDecimal delta);

    @Select("SELECT COALESCE(SUM(balance), 0) FROM t_account")
    BigDecimal totalBalance();

    /**
     * 把某个账户恢复成指定余额。
     *
     * <p>这本该由「重启应用」来完成（data.sql 每次启动都会把余额刷回固定初值），
     * 但手工 curl 反复验证同一个接口时不可能每次都重启，所以留一个重置入口。
     * 截图脚本也用它 —— 保证每次取到的都是同一个起点。
     */
    @Update("UPDATE t_account SET balance = #{balance}, version = 0, updated_at = NOW(3) WHERE id = #{id}")
    int resetBalance(@Param("id") Long id, @Param("balance") BigDecimal balance);

    /**
     * 当前连接 id。两笔操作在不在同一条物理连接上，看它最直接 ——
     * 和「在不在同一个事务里」是两件事，但事务一定要同一条连接才成立。
     */
    @Select("SELECT CONNECTION_ID()")
    Long currentConnectionId();

    @Select("SELECT DATABASE()")
    String currentDatabase();

    /**
     * 当前会话的事务隔离级别。只读事务那一节会用到：
     * MySQL 的默认是 REPEATABLE-READ，而 Spring 的 Isolation.DEFAULT 不会去改它。
     */
    @Select("SELECT @@transaction_isolation")
    String currentIsolationLevel();

    /**
     * 当前会话的只读标记，0 或 1。
     *
     * <p>这个是拿来看「{@code @Transactional(readOnly = true)} 到底有没有跟数据库打招呼」的。
     * Spring 一定会把 {@code readOnly} 记在 {@code TransactionSynchronizationManager} 里，
     * 但它有没有进一步调 {@code Connection.setReadOnly(true)}、驱动又有没有真的发出
     * {@code SET SESSION TRANSACTION READ ONLY}，只有读这个变量才知道。
     * 两者不一致的时候，就说明这个属性只停留在 Java 层。
     */
    @Select("SELECT @@session.transaction_read_only")
    int sessionTransactionReadOnly();
}
