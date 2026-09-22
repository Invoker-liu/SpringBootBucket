-- 建表脚本。由 spring.sql.init.schema-locations 在应用启动时执行。
-- 全部用 CREATE TABLE IF NOT EXISTS：这个脚本每次启动都会跑（mode=always），必须可重复执行。

CREATE TABLE IF NOT EXISTS t_audit_log
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    module     VARCHAR(32)  NOT NULL COMMENT '业务模块',
    action     VARCHAR(64)  NOT NULL COMMENT '操作名',
    method     VARCHAR(128) NOT NULL COMMENT '切面拦到的方法：短签名',
    args       VARCHAR(200) DEFAULT NULL COMMENT '入参摘要，截断到 200 字符',
    status     VARCHAR(8)   NOT NULL COMMENT 'OK / ERROR',
    error      VARCHAR(64)  DEFAULT NULL COMMENT '异常简单类名，仅 ERROR 行有值',
    cost_ms    INT          NOT NULL COMMENT '方法耗时（毫秒）',
    created_at DATETIME(3)  NOT NULL COMMENT '写入时间',
    PRIMARY KEY (id),
    KEY idx_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '切面操作审计日志';
