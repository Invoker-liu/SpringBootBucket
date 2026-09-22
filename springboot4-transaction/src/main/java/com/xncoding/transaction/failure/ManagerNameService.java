package com.xncoding.transaction.failure;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.service.AccountService;
import com.xncoding.transaction.support.TransactionManagerInspector;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 失效场景五：事务管理器的名字。
 *
 * <p>{@code @Transactional} 找事务管理器有两条完全不同的路，这也是这一节的全部内容：
 *
 * <ul>
 *   <li><b>没写名字</b> —— 按类型找容器里<b>唯一</b>的那个
 *       {@code PlatformTransactionManager}。容器里正好有一个，就一切正常；
 *       有两个及以上，直接抛 {@code NoUniqueBeanDefinitionException}。</li>
 *   <li><b>写了名字</b> —— 按名字找 bean。名字对就用它；名字不存在，
 *       抛 {@code NoSuchBeanDefinitionException}。</li>
 * </ul>
 *
 * <p>两个坑都不在启动时暴露，而是在<b>第一次调用那个方法时</b>才炸。
 * 也就是说这类错误能顺利通过编译、通过启动、通过冒烟测试里没覆盖到的那部分接口，
 * 然后在生产上第一次走到那个分支时给你一个 500。想提前发现只能在启动后主动访问一次，
 * 或者写测试覆盖到 —— 这也是本工程每个失效场景都配了接口的原因。
 *
 * <p>{@code @Transactional} 的 {@code value} 和 {@code transactionManager}
 * 互为 {@code @AliasFor}，{@code @Transactional("txManager")} 和
 * {@code @Transactional(transactionManager = "txManager")} 完全是同一件事，
 * 不存在「简写会走另一条路」的说法。
 *
 * <p>顺带说清一个前提：本工程只有一个数据源，所以容器里只有一个事务管理器，
 * 名字由 Boot 的 {@code @Bean} 方法名决定，叫 {@code transactionManager}。
 * 真到了多数据源的环境，「有几个管理器、分别叫什么」必须靠
 * {@code @Bean} 方法名或者 {@code @Bean("xxx")} 显式约定下来，
 * 否则就得靠猜 —— 而猜错不会报错，只会静默地不用事务。
 */
@Service
public class ManagerNameService {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private final AccountService accountService;
    private final TxProbe probe;
    private final TransactionManagerInspector inspector;

    public ManagerNameService(AccountService accountService, TxProbe probe,
                              TransactionManagerInspector inspector) {
        this.accountService = accountService;
        this.probe = probe;
        this.inspector = inspector;
    }

    private void charge(List<StepResult> steps, String label) {
        steps.add(new StepResult(label, probe.summary(), "事务开起来了，转 100.00"));
        accountService.move(1L, 2L, AMOUNT);
    }

    /** 不写名字：按类型找唯一的那个。 */
    @Transactional
    public void anonymous(List<StepResult> steps) {
        steps.add(new StepResult("容器里的事务管理器", "—", inspector.describe()));
        charge(steps, "不写名字");
    }

    /** 写对名字：按名字取 bean。 */
    @Transactional(transactionManager = "transactionManager")
    public void named(List<StepResult> steps) {
        steps.add(new StepResult("显式指定 transactionManager", "—", "名字存在，正常"));
        charge(steps, "写对名字");
    }

    /** 写错名字：调用时才抛 NoSuchBeanDefinitionException。 */
    @Transactional("txManager")
    public void wrongName(List<StepResult> steps) {
        steps.add(new StepResult("准备用 txManager 这个名字取事务管理器", "—",
                "容器里实际有的是：" + inspector.describe()));
        steps.add(new StepResult("下面这一次调用会失败", "—",
                "注意方法体里的转账根本不会执行 —— 事务管理器在进方法之前就要确定"));
        charge(steps, "写错名字");
    }
}
