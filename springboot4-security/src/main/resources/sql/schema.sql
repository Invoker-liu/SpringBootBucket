-- 建表脚本。由 spring.sql.init.schema-locations 在应用启动时执行。
-- 全部用 CREATE TABLE IF NOT EXISTS：这个脚本每次启动都会跑（mode=always），必须可重复执行。

CREATE TABLE IF NOT EXISTS sec_users
(
    username VARCHAR(64)  NOT NULL COMMENT '登录名',
    password VARCHAR(100) NOT NULL COMMENT '密文，带 {bcrypt} 前缀，由 DelegatingPasswordEncoder 分发',
    enabled  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1 可用 0 停用',
    role     VARCHAR(32)  NOT NULL COMMENT '角色名，不带 ROLE_ 前缀：ADMIN / OPERATOR',
    PRIMARY KEY (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '运营管理后台用户';

CREATE TABLE IF NOT EXISTS t_audit_log
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(64)  NOT NULL COMMENT '操作人',
    action     VARCHAR(64)  NOT NULL COMMENT '操作名',
    order_no   VARCHAR(32)  DEFAULT NULL COMMENT '关联订单号',
    result     VARCHAR(16)  NOT NULL COMMENT 'OK / DENIED / ERROR',
    detail     VARCHAR(200) DEFAULT NULL COMMENT '补充说明',
    created_at DATETIME(3)  NOT NULL COMMENT '写入时间',
    PRIMARY KEY (id),
    KEY idx_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '敏感操作审计';
