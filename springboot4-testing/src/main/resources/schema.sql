CREATE TABLE IF NOT EXISTS orders (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no   VARCHAR(32)  NOT NULL UNIQUE,
    amount     DECIMAL(10, 2) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
