-- 订单表（下单接口的数据源，异步三任务围绕它展开）
CREATE TABLE IF NOT EXISTS po_order (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    order_no   VARCHAR(32)   NOT NULL COMMENT '订单号',
    amount     DECIMAL(12,2) NOT NULL COMMENT '金额',
    exec_mode  VARCHAR(8)    NOT NULL DEFAULT 'ASYNC' COMMENT 'ASYNC/SYNC',
    created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下单时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单';
