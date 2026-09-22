-- 交易库（pos）建表脚本
--
-- 两个库都有 t_user，表结构完全一样、id 也一样，只有数据不同。
-- 这是故意的：查 id=1 拿到的是 admin 还是 admin1，一眼就能看出数据源切没切对。
-- 如果用「不同的表名」来区分，切错了只会得到一个表不存在，分不清是路由错了还是脚本没跑。

CREATE TABLE IF NOT EXISTS t_user (
    id        BIGINT      NOT NULL,
    username  VARCHAR(64) NOT NULL,
    real_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '交易库用户（探针表）';

CREATE TABLE IF NOT EXISTS t_order (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(32)   NOT NULL,
    customer_name VARCHAR(64)   NOT NULL,
    total_amount  DECIMAL(12, 2) NOT NULL,
    status        VARCHAR(16)   NOT NULL,
    created_at    DATETIME(3)   NOT NULL,
    version       INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '订单';

CREATE TABLE IF NOT EXISTS t_order_item (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    order_id     BIGINT         NOT NULL,
    product_name VARCHAR(64)    NOT NULL,
    quantity     INT            NOT NULL,
    price        DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_id (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '订单明细';
