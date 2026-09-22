package com.xncoding.batch.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入库后的对账订单（recon_order 表一行）。
 */
public class ReconOrder {

    private Long id;
    private String jobExecutionId;
    private String orderNo;
    private String merchant;
    private BigDecimal amount;
    private String status;
    private LocalDateTime reconTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJobExecutionId() {
        return jobExecutionId;
    }

    public void setJobExecutionId(String jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getMerchant() {
        return merchant;
    }

    public void setMerchant(String merchant) {
        this.merchant = merchant;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getReconTime() {
        return reconTime;
    }

    public void setReconTime(LocalDateTime reconTime) {
        this.reconTime = reconTime;
    }
}
