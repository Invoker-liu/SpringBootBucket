-- ============================================================
-- springboot4-mybatis 建库建表脚本
--
-- 执行方式（只需一次）：
--   mysql -h 192.168.1.97 -P 3306 -uroot -proot123456 < schema.sql
--
-- 脚本可重复执行：会先删表再重建，并写入 6 条演示数据。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `springboot4_mybatis`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `springboot4_mybatis`;

DROP TABLE IF EXISTS `t_order`;

CREATE TABLE `t_order`
(
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`       VARCHAR(32)   NOT NULL COMMENT '订单号，服务端生成',
    `customer_name`  VARCHAR(64)   NOT NULL COMMENT '客户姓名',
    `customer_phone` VARCHAR(20)   NOT NULL COMMENT '客户手机号',
    `total_amount`   DECIMAL(12, 2) NOT NULL COMMENT '订单金额',
    `status`         VARCHAR(16)   NOT NULL COMMENT '订单状态，存枚举名',
    `remark`         VARCHAR(255)           DEFAULT NULL COMMENT '备注',
    `items`          VARCHAR(1000)          DEFAULT NULL COMMENT '订单明细，JSON 数组，由 Jackson3TypeHandler 读写',
    `created_at`     DATETIME(3)   NOT NULL COMMENT '创建时间，由 MetaObjectHandler 填充',
    `updated_at`     DATETIME(3)   NOT NULL COMMENT '更新时间，由 MetaObjectHandler 填充',
    `deleted`        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 1 已删除',
    `version`        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_status_created_at` (`status`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '订单表';

-- ------------------------------------------------------------
-- 演示数据：覆盖全部 5 个状态，便于验证分页与聚合统计
-- ------------------------------------------------------------
INSERT INTO `t_order` (`order_no`, `customer_name`, `customer_phone`, `total_amount`, `status`, `remark`, `items`,
                       `created_at`, `updated_at`, `deleted`, `version`)
VALUES ('ORD20260901000001', '熊大', '13800138000', 299.50, 'CREATED', '演示数据',
        '[{"name":"机械键盘","quantity":1,"price":299.50}]', NOW(3), NOW(3), 0, 0),
       ('ORD20260901000002', '熊二', '13800138001', 1280.00, 'PAID', '演示数据',
        '[{"name":"显示器","quantity":1,"price":1180.00},{"name":"支架","quantity":2,"price":50.00}]', NOW(3), NOW(3), 0, 0),
       ('ORD20260901000003', '张三', '13900139000', 68.80, 'SHIPPED', '演示数据',
        '[{"name":"鼠标垫","quantity":1,"price":68.80}]', NOW(3), NOW(3), 0, 0),
       ('ORD20260901000004', '李四', '13900139001', 4599.00, 'COMPLETED', '演示数据',
        '[{"name":"笔记本","quantity":1,"price":4599.00}]', NOW(3), NOW(3), 0, 0),
       ('ORD20260901000005', '王五', '13700137000', 199.00, 'CANCELLED', '演示数据', NULL, NOW(3), NOW(3), 0, 0),
       ('ORD20260901000006', '赵六', '13700137001', 888.00, 'PAID', '演示数据',
        '[{"name":"耳机","quantity":2,"price":444.00}]', NOW(3), NOW(3), 0, 0);

SELECT COUNT(*) AS `已写入行数` FROM `t_order`;
