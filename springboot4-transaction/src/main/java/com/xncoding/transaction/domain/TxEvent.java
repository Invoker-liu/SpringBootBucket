package com.xncoding.transaction.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 事务事件。{@code @TransactionalEventListener} 的四个相位各写一条，
 * 「事件到底在提交前还是提交后被触发」这件事，只有落库才看得见。
 */
@TableName("t_tx_event")
public class TxEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发起事件的那个事务的名字，取的是 {@code TransactionSynchronizationManager.getCurrentTransactionName()}。 */
    private String txName;

    private String phase;

    private String detail;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTxName() {
        return txName;
    }

    public void setTxName(String txName) {
        this.txName = txName;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
