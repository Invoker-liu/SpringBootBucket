package com.xncoding.transaction.failure;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.exception.BalanceNotEnoughException;
import com.xncoding.transaction.exception.TransferRejectedException;
import com.xncoding.transaction.service.AccountService;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.util.List;

/**
 * 失效场景三：异常被自己吃掉了。
 *
 * <p>事务回滚的判定完全建立在「有没有异常穿出被代理的那个方法」上。
 * 只要异常在方法内部被 {@code catch} 住、不再往外抛，事务管理器就永远不知道出过事，
 * 到点照常提交。程序不报错、日志没异常、数据已经错了 —— 这个坑最难排查的地方
 * 在于它<b>没有任何症状</b>。
 *
 * <p>更隐蔽的是最后那个方法：{@code catch} 之后重新抛，但是包成了<b>受检异常</b>。
 * 看起来「我明明抛出去了」，实际上受检异常默认不回滚，一样白搭。
 * 一个「为了匹配方法签名」而顺手做的类型转换，就能把回滚弄丢。
 *
 * <p>两种正确写法都在这个类里：
 * <ul>
 *   <li>继续往上抛运行时异常（让事务的判定逻辑正常工作）；</li>
 *   <li>或者吞掉异常但显式调 {@code setRollbackOnly()} —— 这个写法适合
 *       「异常要转成业务返回值，但事务必须回滚」的场景，比如批量导入时
 *       逐条 try-catch 收集失败原因，最后统一返回。注意收集失败原因<b>不能</b>
 *       写在同一张事务表里，否则会跟着一起回滚，得另开事务或者写到外面去。</li>
 * </ul>
 */
@Service
public class SwallowedExceptionService {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private final AccountService accountService;
    private final TxProbe probe;

    public SwallowedExceptionService(AccountService accountService, TxProbe probe) {
        this.accountService = accountService;
        this.probe = probe;
    }

    /** 错误示范：catch 住了就不管了。 */
    @Transactional
    public void swallow(List<StepResult> steps) {
        steps.add(new StepResult("事务已开启", probe.summary(), "开始转账"));
        accountService.move(1L, 2L, AMOUNT);
        steps.add(new StepResult("已从熊大转出 100.00", probe.summary(), "接下来模拟一次业务失败"));
        try {
            throw new BalanceNotEnoughException("业务上失败了，但我不告诉你");
        } catch (BalanceNotEnoughException e) {
            steps.add(new StepResult("异常被 catch 住了", probe.summary(),
                    "没有往外抛，事务管理器不知道出过事"));
        }
        steps.add(new StepResult("方法正常返回", probe.summary(), "事务会当成成功提交"));
    }

    /** 正确写法一：抛出去。 */
    @Transactional
    public void rethrow(List<StepResult> steps) {
        steps.add(new StepResult("事务已开启", probe.summary(), "开始转账"));
        accountService.move(1L, 2L, AMOUNT);
        try {
            throw new BalanceNotEnoughException("业务上失败了");
        } catch (BalanceNotEnoughException e) {
            steps.add(new StepResult("catch 之后重新抛出", probe.summary(), "异常会穿出方法"));
            throw e;
        }
    }

    /** 错误示范的变体：包成了受检异常。类型一换，回滚就没了。 */
    @Transactional
    public void rethrowAsChecked(List<StepResult> steps) throws TransferRejectedException {
        steps.add(new StepResult("事务已开启", probe.summary(), "开始转账"));
        accountService.move(1L, 2L, AMOUNT);
        try {
            throw new BalanceNotEnoughException("业务上失败了");
        } catch (BalanceNotEnoughException e) {
            steps.add(new StepResult("catch 之后包成受检异常再抛", probe.summary(),
                    "受检异常默认不回滚，等于白抛"));
            throw new TransferRejectedException("包装后的受检异常", e);
        }
    }

    /** 正确写法二：吞掉异常，但手动标记回滚。 */
    @Transactional
    public void swallowButMarkRollbackOnly(List<StepResult> steps) {
        steps.add(new StepResult("事务已开启", probe.summary(), "开始转账"));
        accountService.move(1L, 2L, AMOUNT);
        try {
            throw new BalanceNotEnoughException("业务上失败了");
        } catch (BalanceNotEnoughException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            steps.add(new StepResult("异常被 catch，但事务已标记 rollback-only", probe.summary(),
                    "方法会正常返回，事务在提交阶段看到这个标记就改成回滚"));
        }
        steps.add(new StepResult("方法正常返回", probe.summary(), "返回值和回滚互不冲突"));
    }
}
