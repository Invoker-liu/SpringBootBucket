-- 订单表（超时取消任务的数据源）
CREATE TABLE IF NOT EXISTS po_order (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(32)   NOT NULL COMMENT '订单号',
    amount       DECIMAL(12,2) NOT NULL COMMENT '金额',
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT 'PENDING_PAYMENT/PAID/CANCELLED',
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下单时间',
    cancel_time  DATETIME(3)   NULL COMMENT '取消时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单';

-- 对账文件登记表（cron 扫描任务的数据源）
CREATE TABLE IF NOT EXISTS recon_file (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    file_name    VARCHAR(128)  NOT NULL COMMENT '对账文件名',
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DONE',
    received_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '登记时间',
    processed_at DATETIME(3)   NULL COMMENT '处理完成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_name (file_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账文件登记';

-- 每日报表（fixedRate 统计任务的落点）
CREATE TABLE IF NOT EXISTS daily_report (
    stat_date    DATE           NOT NULL COMMENT '统计日期',
    order_count  INT            NOT NULL COMMENT '订单数',
    total_amount DECIMAL(14,2)  NOT NULL COMMENT '订单总金额',
    built_at     DATETIME(3)    NOT NULL COMMENT '本次统计时间',
    PRIMARY KEY (stat_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '每日订单报表';
