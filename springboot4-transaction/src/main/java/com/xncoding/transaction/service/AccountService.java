package com.xncoding.transaction.service;

import com.xncoding.transaction.domain.Account;
import com.xncoding.transaction.dto.AccountView;
import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.exception.BalanceNotEnoughException;
import com.xncoding.transaction.mapper.AccountMapper;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账户基础操作。
 *
 * <p>这个类【没有】{@code @Transactional}，是故意的：它是被各个带事务的服务复用的
 * 数据访问层。这样设计之后，事务边界全部落在调用它的那些方法上，
 * 「谁开的事务」这件事在代码里一眼可见，不会藏在某个下层方法里。
 *
 * <p>它是全局唯一的「读余额」出口，所有场景接口都从这里拿执行后的余额，
 * 保证大家看到的是同一份数据。
 */
@Service
public class AccountService {

    /** 四个账户的固定初值，和 data.sql 里的种子数据一致。重置接口也按这个来。 */
    private static final List<BigDecimal> INITIAL_BALANCES = List.of(
            new BigDecimal("1000.00"),
            new BigDecimal("500.00"),
            new BigDecimal("300.00"),
            new BigDecimal("200.00"));

    private final AccountMapper accountMapper;
    private final TxProbe probe;

    public AccountService(AccountMapper accountMapper, TxProbe probe) {
        this.accountMapper = accountMapper;
        this.probe = probe;
    }

    public List<AccountView> accounts() {
        return accountMapper.findAll().stream()
                .map(a -> new AccountView(a.getId(), a.getOwner(), a.getBalance()))
                .toList();
    }

    public BigDecimal total() {
        return accountMapper.totalBalance();
    }

    /** 转账的基本动作：减一个、加一个。注意这里【没有】检查余额，检查交给调用方。 */
    public void move(Long fromId, Long toId, BigDecimal amount) {
        accountMapper.addBalance(fromId, amount.negate());
        accountMapper.addBalance(toId, amount);
    }

    public void requireBalance(Long accountId, BigDecimal amount) {
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BalanceNotEnoughException("账户不存在：" + accountId);
        }
        if (account.getBalance().compareTo(amount) < 0) {
            throw new BalanceNotEnoughException(
                    "%s 余额 %s 不足 %s".formatted(account.getOwner(), account.getBalance(), amount));
        }
    }

    /** 记录一步。事务状态由探针现场读取，不是调用方传进来的。 */
    public StepResult step(String label, String detail) {
        return new StepResult(label, probe.summary(), detail);
    }

    public ScenarioResult build(String scenario, String expectation,
                                List<StepResult> steps, String conclusion) {
        return new ScenarioResult(scenario, expectation, steps, conclusion, accounts(), total());
    }

    /** 把四个账户恢复成固定初值。手工反复验证同一个场景时用。 */
    public void reset() {
        for (int i = 0; i < INITIAL_BALANCES.size(); i++) {
            accountMapper.resetBalance((long) (i + 1), INITIAL_BALANCES.get(i));
        }
    }
}
