package com.xncoding.multisource.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 探针用户。两个库各有一张同名同结构的 {@code t_user}，
 * 表里的数据不同，用来证明「这次请求到底落在哪个库」。
 *
 * <p>只靠 {@code username} 判断还不够硬 —— 万一两边的数据被人改过就说不清了。
 * 所以额外带上两个数据库自己报出来的值（{@code DATABASE()} 与
 * {@code CONNECTION_ID()}），这两个是<b>连接</b>的属性，不是数据的属性，
 * 没法被数据污染，指哪打哪。见 {@code mapper/probe/ProbeUserMapper.xml}。
 */
@TableName("t_user")
public class ProbeUser {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String username;

    private String realName;

    /** 当前连接所在的库名，由 MySQL 的 DATABASE() 返回。 */
    @TableField(exist = false)
    private String databaseName;

    /** 当前连接的 MySQL 连接号。同一次请求的不同库，这个值必然不同。 */
    @TableField(exist = false)
    private Long connectionId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public Long getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(Long connectionId) {
        this.connectionId = connectionId;
    }
}
