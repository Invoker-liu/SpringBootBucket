package com.xncoding.restclient.model;

public record RiskResult(boolean pass, int score, String reason) {
}
