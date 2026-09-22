package com.xncoding.transaction.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户。转账是本篇的基本动作：减一个、加一个，两句话要么都成、要么都不成。
 *
 * <p>余额用 DECIMAL(12,2) 而不是 DOUBLE，这是钱的老规矩：二进制浮点存不下 0.1，
 * 反复加减之后余额会出现 0.30000000000000004 这种尾巴，回滚对比的时候很难看。
 */
@TableName("t_account")
public class Account {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String owner;

    private BigDecimal balance;

    /** 乐观锁版本号。本篇只在一处用到它，用来演示「事务里的读是快照读还是当前读」。 */
    private Integer version;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
