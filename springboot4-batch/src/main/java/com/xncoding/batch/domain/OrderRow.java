package com.xncoding.batch.domain;

/**
 * 对账文件里的一行订单（CSV 原始形态）。
 */
public class OrderRow {

    private String orderNo;
    private String merchant;
    private String amount;
    private String status;

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

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return orderNo + "/" + merchant + "/" + amount + "/" + status;
    }
}
