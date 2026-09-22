package com.xncoding.transaction.controller;

import com.xncoding.transaction.dto.AccountOverview;
import com.xncoding.transaction.dto.TransactionManagerReport;
import com.xncoding.transaction.service.AccountService;
import com.xncoding.transaction.support.TransactionManagerInspector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户查询与重置。整个类没有任何事务注解，是故意的 ——
 * 这里读到的余额必须是「已经落库、别人也看得到」的值，也就是事务之外的值。
 *
 * <p>如果给这个类的读方法加上 {@code @Transactional(readOnly = true)}，
 * 它仍然读得到正确结果（因为此时没有别的事务在改数据），
 * 但它就失去了「事务外视角」这个身份 —— 后面所有实验都拿它当裁判，
 * 裁判自己站在场内是不行的。
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final TransactionManagerInspector inspector;

    public AccountController(AccountService accountService, TransactionManagerInspector inspector) {
        this.accountService = accountService;
        this.inspector = inspector;
    }

    /** 当前余额。每个实验做完都该看一眼它。 */
    @GetMapping
    public AccountOverview overview() {
        return new AccountOverview(accountService.accounts(), accountService.total());
    }

    /**
     * 把四个账户刷回固定初值。
     *
     * <p>等价于「重启一次应用」（启动时 data.sql 也会这么干），
     * 但手工反复验证时不可能每次重启，所以留个入口。
     */
    @PostMapping("/reset")
    public AccountOverview reset() {
        accountService.reset();
        return overview();
    }

    /** 容器里有几个事务管理器、分别叫什么。多数据源排查的第一步。 */
    @GetMapping("/transaction-managers")
    public TransactionManagerReport transactionManagers() {
        return new TransactionManagerReport(inspector.count(), inspector.describe());
    }
}
