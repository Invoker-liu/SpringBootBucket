CREATE TABLE IF NOT EXISTS po_order (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    order_no   VARCHAR(32)  NOT NULL,
    amount     DECIMAL(10, 2) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
