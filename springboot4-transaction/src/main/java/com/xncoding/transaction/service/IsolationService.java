package com.xncoding.transaction.service;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 隔离级别。
 *
 * <p>四个方法除了 {@code isolation} 之外完全一样，返回的也是同一个探针的读数。
 * 摆在一起对照，能看出两件事：
 *
 * <ul>
 *   <li>{@code Isolation.DEFAULT} 的真实含义是「<b>什么都不设</b>」，不是「设成数据库默认值」。
 *       Spring 侧读出来是 null，连接上也不会执行 {@code SET TRANSACTION ISOLATION LEVEL}，
 *       MySQL 那边保持会话默认的 REPEATABLE-READ。结果看着一样，过程完全不同 ——
 *       这也是为什么换一个默认级别不是 REPEATABLE-READ 的数据库时，
 *       「我用 DEFAULT 就够了」的代码行为会变。</li>
 *   <li>非 DEFAULT 的级别由 Spring 在<b>事务开始、借到连接之后</b>
 *       通过 {@code SET TRANSACTION ISOLATION LEVEL} 落到这条连接上。
 *       既然是「落到连接上」，就有个前提：它必须是<b>新事务</b>。
 *       加进别人的事务时（比如传播行为是 REQUIRED 而外层已经开了事务），
 *       连接早就借好了，这句 SET 不会再执行，声明的级别被静默忽略。</li>
 * </ul>
 *
 * <p>另外要说清楚边界：隔离级别改的是「这条连接上后续事务的可见性规则」，
 * 属于数据库的事务语义，不是 Spring 发明的。这里能验证的只是
 * 「Spring 有没有把它落到连接上」。真正的脏读、不可重复读、幻读需要两条连接交错操作
 * 才能复现，单个 HTTP 请求做不到 —— 那属于数据库教材的内容，本篇不铺开，
 * 免得把事务的「配置层」和「并发语义层」混成一锅。
 */
@Service
public class IsolationService {

    private final TxProbe probe;

    public IsolationService(TxProbe probe) {
        this.probe = probe;
    }

    private void readIsolation(List<StepResult> steps, String label) {
        steps.add(new StepResult(label, probe.summary(), probe.isolationSummary()));
    }

    /** 对照组：什么都不声明。 */
    @Transactional(isolation = Isolation.DEFAULT)
    public void defaultLevel(List<StepResult> steps) {
        readIsolation(steps, "isolation = DEFAULT");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void readCommitted(List<StepResult> steps) {
        readIsolation(steps, "isolation = READ_COMMITTED");
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void repeatableRead(List<StepResult> steps) {
        readIsolation(steps, "isolation = REPEATABLE_READ");
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void serializable(List<StepResult> steps) {
        readIsolation(steps, "isolation = SERIALIZABLE");
    }
}
