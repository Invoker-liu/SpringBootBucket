-- 运营库（biz）建表脚本
--
-- t_user 与 pos 库里那张同名同结构，靠数据区分。见 pos-schema.sql 的说明。

CREATE TABLE IF NOT EXISTS t_user (
    id        BIGINT      NOT NULL,
    username  VARCHAR(64) NOT NULL,
    real_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '运营库用户（探针表）';

CREATE TABLE IF NOT EXISTS t_product (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    product_name VARCHAR(64)    NOT NULL,
    category     VARCHAR(32)    NOT NULL,
    price        DECIMAL(12, 2) NOT NULL,
    stock        INT            NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_name (product_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品主数据';
