package com.xncoding.transaction.failure;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.exception.BalanceNotEnoughException;
import com.xncoding.transaction.service.AccountService;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 失效场景四：换了个线程。
 *
 * <p>Spring 的事务状态存在 {@code ThreadLocal} 里 —— 这一点决定了它天生跨不了线程。
 * 主线程开了事务，然后用线程池（或 {@code @Async}、{@code CompletableFuture}、
 * 并行流）丢一段写库的逻辑出去，那段逻辑在新线程上跑，读不到主线程的事务，
 * 于是自己从连接池借一条新连接、以自动提交的方式执行完就完事了。
 *
 * <p>结果就是：主线程回滚了，子线程那笔稳稳当当地留在库里。而且它不会报错 ——
 * 子线程确实成功了，它是被主线程连累才显得多余。
 *
 * <p>这里用虚拟线程做实验（Java 21 的 {@code newVirtualThreadPerTaskExecutor}），
 * 只是想说明「线程模型换成虚拟线程也改变不了这件事」：事务绑的是线程，
 * 虚拟线程同样是另一个线程。换成平台线程池、{@code @Async}，
 * 或者 RocketMQ / 线程池消费者里的逻辑，问题一模一样。
 *
 * <p>正确做法不是「想办法让事务传过去」（传不过去，也不该传），而是
 * <b>让子线程有自己的事务边界</b>：把 {@code TransactionTemplate} 传进子线程里执行，
 * 子线程的工作就变成一个独立的、会提交也会回滚的事务。它和主事务之间没有原子性，
 * 这是异步化的必然代价，得靠业务设计（补偿、对账、最终一致）去兜，不能靠配置绕过去。
 */
@Service
public class ChildThreadService {

    /** 主线程转出的金额。 */
    private static final BigDecimal OUTER_AMOUNT = new BigDecimal("100.00");

    /** 子线程转出的金额。刻意不同，方便在余额上区分是谁写进去的。 */
    private static final BigDecimal CHILD_AMOUNT = new BigDecimal("50.00");

    private final AccountService accountService;
    private final TxProbe probe;
    private final TransactionTemplate template;

    public ChildThreadService(AccountService accountService, TxProbe probe, TransactionTemplate template) {
        this.accountService = accountService;
        this.probe = probe;
        this.template = template;
    }

    /**
     * 错误示范：子线程裸奔。
     *
     * <p>主线程操作 1 号 → 2 号，子线程操作 3 号 → 4 号。
     * 分成两组账户不是随意的：如果两边都动同一对账户，子线程会去抢主线程还没提交的行锁，
     * 整个实验会卡在锁等待上，看不到想看的结论。
     */
    @Transactional
    public void writeInChildThread(List<StepResult> steps) throws Exception {
        steps.add(new StepResult("主线程 · 事务已开启", probe.summary(), "先转出 100.00"));
        accountService.move(1L, 2L, OUTER_AMOUNT);
        steps.add(new StepResult("主线程 · 已转出 100.00", probe.summary(),
                "这笔在主线程的事务里，还没提交"));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> future = executor.submit(() -> {
                String childState = probe.summary();
                accountService.move(3L, 4L, CHILD_AMOUNT);
                return childState;
            });
            String childState = future.get();
            steps.add(new StepResult("子线程 · 已转出 50.00",
                    "子线程读到的事务状态：" + childState,
                    "子线程不在主线程的事务里，这条 UPDATE 执行完就自动提交了"));
        }

        steps.add(new StepResult("主线程 · 即将抛异常", probe.summary(),
                "主线程那 100.00 会回滚，子线程那 50.00 不会"));
        throw new BalanceNotEnoughException("主线程故意失败");
    }

    /**
     * 正确示范：把事务模板传进子线程，让子线程拥有自己的事务边界。
     *
     * <p>这么做不会让两笔操作变成原子的（跨线程没有原子性可言），
     * 但至少子线程里那部分要么一起成功、要么一起回滚，不会写一半。
     */
    @Transactional
    public void writeInChildThreadWithOwnTransaction(List<StepResult> steps) throws Exception {
        steps.add(new StepResult("主线程 · 事务已开启", probe.summary(), "先转出 100.00"));
        accountService.move(1L, 2L, OUTER_AMOUNT);
        steps.add(new StepResult("主线程 · 已转出 100.00", probe.summary(), "接下来交给子线程"));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> future = executor.submit(() -> {
                String[] innerState = new String[1];
                template.executeWithoutResult(status -> {
                    innerState[0] = probe.summary();
                    accountService.move(3L, 4L, CHILD_AMOUNT);
                });
                return innerState[0];
            });
            steps.add(new StepResult("子线程 · 在模板里转出 50.00",
                    "子线程事务内的状态：" + future.get(),
                    "子线程自己开了事务，扣款和入账在同一个事务里"));
        }

        steps.add(new StepResult("主线程 · 即将抛异常", probe.summary(),
                "主线程那 100.00 回滚，子线程的事务已经独立提交了"));
        throw new BalanceNotEnoughException("主线程故意失败");
    }
}
