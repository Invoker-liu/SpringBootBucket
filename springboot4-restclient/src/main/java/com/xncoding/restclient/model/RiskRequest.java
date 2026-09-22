package com.xncoding.restclient.model;

import java.math.BigDecimal;

public record RiskRequest(String orderNo, BigDecimal amount) {
}
