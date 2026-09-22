-- 对账结果表（业务表；IF NOT EXISTS 保证重复启动幂等）
CREATE TABLE IF NOT EXISTS recon_order (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(32)  NOT NULL COMMENT '订单号',
    merchant     VARCHAR(64)  NOT NULL COMMENT '商户',
    amount       DECIMAL(12, 2) NOT NULL COMMENT '金额',
    status       VARCHAR(16)  NOT NULL COMMENT 'PAID/REFUND/SETTLED',
    recon_time   DATETIME(3)  NOT NULL COMMENT '入库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账订单';
