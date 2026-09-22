-- 建表脚本。由 spring.sql.init.schema-locations 在应用启动时执行。
--
-- 全部用 CREATE TABLE IF NOT EXISTS：这个脚本每次启动都会跑（mode=always），
-- 必须可重复执行。清数据的活交给 data.sql，它用 DELETE + INSERT。

CREATE TABLE IF NOT EXISTS t_account
(
    id         BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    owner      VARCHAR(32)    NOT NULL COMMENT '户主',
    balance    DECIMAL(12, 2) NOT NULL COMMENT '余额',
    version    INT            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    updated_at DATETIME(3)    NOT NULL COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner (owner)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '账户表，用来演示转账事务';

-- 事务事件表。@TransactionalEventListener 的四个相位各写一条，
-- 「事件什么时候被触发」这件事只有落库才看得见。
CREATE TABLE IF NOT EXISTS t_tx_event
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    -- 事务名是 Spring 拼出来的「全限定类名.方法名」，比一般人想的要长：
    -- com.xncoding.transaction.service.TransactionEventService.publishAndCommit 就有 74 个字符，
    -- 所以这里留 160。用 VARCHAR(64) 的话会在插入时报 Data too long。
    tx_name    VARCHAR(160) NOT NULL COMMENT '发起事件的事务名',
    phase      VARCHAR(24)  NOT NULL COMMENT '事务相位：BEFORE_COMMIT / AFTER_COMMIT / AFTER_ROLLBACK / AFTER_COMPLETION',
    detail     VARCHAR(200) DEFAULT NULL COMMENT '附带说明',
    created_at DATETIME(3)  NOT NULL COMMENT '写入时间',
    PRIMARY KEY (id),
    KEY idx_tx_name (tx_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '事务事件表';
