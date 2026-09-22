package com.xncoding.transaction.dto;

import java.math.BigDecimal;

/** 账户的对外视图。 */
public record AccountView(Long id, String owner, BigDecimal balance) {
}
