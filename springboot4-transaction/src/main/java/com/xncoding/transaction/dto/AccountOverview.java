package com.xncoding.transaction.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账户总览。{@code GET /api/accounts} 的响应体。
 *
 * <p>带上 {@code total} 是因为转账不改变总额：四个账户的余额之和永远是 2000.00。
 * 每次读余额都把它一起返回，等于每做一次实验都顺手做了一次对账 ——
 * 如果这个数字变了，说明有笔转账只落了一半，比单独看某个账户的余额更早发现问题。
 */
public record AccountOverview(List<AccountView> accounts, BigDecimal total) {
}
