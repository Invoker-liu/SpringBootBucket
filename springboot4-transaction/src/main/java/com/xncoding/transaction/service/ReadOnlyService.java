package com.xncoding.transaction.service;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 只读事务。
 *
 * <p>{@code @Transactional(readOnly = true)} 是面试里出现频率很高、被误解得也很厉害的一个属性。
 * 它的作用链有三层，这一组接口把三层都读出来了：
 *
 * <ol>
 *   <li><b>Spring 层</b> —— 把 {@code readOnly} 记进 {@code TransactionSynchronizationManager}，
 *       并调一次 {@code Connection.setReadOnly(true)}；
 *       探针的「Spring 声明」读的就是这里。</li>
 *   <li><b>驱动层</b> —— 连接池把它转发给 JDBC 驱动。MySQL Connector/J 会因此发出
 *       {@code SET SESSION TRANSACTION READ ONLY}，并且在自己的 {@code StatementImpl}
 *       里加一道客户端校验：只要连接是只读的，执行 UPDATE / DELETE / INSERT 就直接抛错。
 *       探针的「数据库会话」读的是 {@code @@session.transaction_read_only}，能验证前半个动作。</li>
 *   <li><b>数据库层</b> —— 真的收到只读事务里的写请求时也会拒绝。</li>
 * </ol>
 *
 * <p>所以「设了 readOnly 就写不了」在这套技术栈上<b>是成立的</b>，
 * 被拦下来的时候既不是 MySQL 报的错、也不是 Spring 报的错，而是 Connector/J 自己拦的
 * （错误文案 {@code Connection is read-only. Queries leading to data modification are not allowed.}
 * 就写在 {@code com.mysql.cj.LocalizedErrorMessages.properties} 里，由
 * {@code com.mysql.cj.jdbc.StatementImpl} 抛出）。
 *
 * <p>但别把它当成安全机制：换一个驱动、换一个连接池，第 2 层就可能什么都不做，
 * 那这行注解就只剩语义了。它真正靠得住的价值是<b>给人看的</b> ——
 * 读代码的人知道这里不该写；同时也是给上层框架的信号（JPA/Hibernate 据此把
 * FlushMode 设成 NEVER 跳过脏检查，读写分离的中间件据此把连接路由到只读副本）。
 *
 * <p>还要注意一个生效前提：{@code Connection.setReadOnly} 是在<b>事务开始、借到连接之后</b>
 * 执行的。如果当前方法是加进外层已有事务的，连接早就借好了，这个调用不会再发生。
 */
@Service
public class ReadOnlyService {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private final AccountService accountService;
    private final TxProbe probe;

    public ReadOnlyService(AccountService accountService, TxProbe probe) {
        this.accountService = accountService;
        this.probe = probe;
    }

    /** 对照组：普通事务。读余额，不打 readOnly 标记。 */
    @Transactional
    public void writableRead(List<StepResult> steps) {
        steps.add(accountService.step("普通事务里读余额", "readOnly 标记：" + probe.readOnlySummary()));
        accountService.accounts();
    }

    /** 只读事务：读余额，把 readOnly 标记打成 true。 */
    @Transactional(readOnly = true)
    public void readOnlyRead(List<StepResult> steps) {
        steps.add(accountService.step("只读事务里读余额", "readOnly 标记：" + probe.readOnlySummary()));
        accountService.accounts();
    }

    /**
     * 只读事务里做一次写操作。
     *
     * <p>这个接口是整节的重点：它会被拦下来。异常来自 JDBC 驱动而不是数据库，
     * 而且它是 RuntimeException（经 MyBatis 翻译成 {@code TransientDataAccessResourceException}），
     * 所以事务还会顺带回滚 —— 这一点和「受检异常不回滚」那条规则正好错开，
     * 不要混起来记。
     */
    @Transactional(readOnly = true)
    public void readOnlyWrite(List<StepResult> steps) {
        steps.add(accountService.step("只读事务开始", "readOnly 标记：" + probe.readOnlySummary()));
        accountService.move(1L, 2L, AMOUNT);
        steps.add(accountService.step("从熊大往熊二转 100.00", "走到这里说明写操作没被拦"));
    }
}
