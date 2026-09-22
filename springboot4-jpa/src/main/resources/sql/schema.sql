-- ============================================================
-- springboot4-jpa 建库建表脚本
--
-- 执行方式（只需一次）：
--   mysql -h 192.168.1.97 -P 3306 -uroot -proot123456 < schema.sql
--
-- 脚本可重复执行：先删表再重建，并写入 5 条演示数据。
--
-- 注意两张表都有 deleted 列：实体上标了 Hibernate 7 的 @SoftDelete，
-- 删除动作会变成 UPDATE ... SET deleted = 1，记录永远不物理消失。
-- 列类型用 BIT(1)，与 Hibernate 把 Java boolean 映射到 MySQL 的默认类型一致，
-- 否则 spring.jpa.hibernate.ddl-auto=validate 会在启动时报列类型不匹配。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `springboot4_jpa`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `springboot4_jpa`;

-- 先删子表再删主表，避免外键报错
DROP TABLE IF EXISTS `t_order_item`;
DROP TABLE IF EXISTS `t_order`;

CREATE TABLE `t_order`
(
    `id`             BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`       VARCHAR(32)    NOT NULL COMMENT '订单号，服务端生成',
    `customer_name`  VARCHAR(64)    NOT NULL COMMENT '客户姓名',
    `customer_phone` VARCHAR(20)    NOT NULL COMMENT '客户手机号',
    `total_amount`   DECIMAL(12, 2) NOT NULL COMMENT '订单金额',
    `status`         VARCHAR(16)    NOT NULL COMMENT '订单状态，存枚举名',
    `remark`         VARCHAR(255)            DEFAULT NULL COMMENT '备注',
    `deleted`        BIT(1)         NOT NULL DEFAULT b'0' COMMENT '软删除标记，由 @SoftDelete 维护',
    `version`        INT            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，由 @Version 维护',
    `created_at`     DATETIME(3)    NOT NULL COMMENT '创建时间，由 Spring Data JPA 审计填充',
    `updated_at`     DATETIME(3)    NOT NULL COMMENT '更新时间，由 Spring Data JPA 审计填充',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_status` (`status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '订单表';

CREATE TABLE `t_order_item`
(
    `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`     BIGINT         NOT NULL COMMENT '所属订单',
    `product_name` VARCHAR(128)   NOT NULL COMMENT '商品名称',
    `price`        DECIMAL(12, 2) NOT NULL COMMENT '单价',
    `quantity`     INT            NOT NULL COMMENT '数量',
    `deleted`      BIT(1)         NOT NULL DEFAULT b'0' COMMENT '软删除标记，由 @SoftDelete 维护',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    CONSTRAINT `fk_item_order` FOREIGN KEY (`order_id`) REFERENCES `t_order` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '订单明细表';

-- ------------------------------------------------------------
-- 演示数据：覆盖 4 个状态，其中 ORD2026091801 是唯一一个带多条明细的订单，
-- 用来演示一次性抓取关联集合（避免 N+1）。
-- ------------------------------------------------------------

INSERT INTO `t_order` (`order_no`, `customer_name`, `customer_phone`, `total_amount`, `status`, `remark`, `version`, `created_at`, `updated_at`)
VALUES ('ORD2026091801', '熊大', '13800138000', 599.00, 'CREATED', '演示下单，含两条明细', 0, '2026-09-18 09:00:00.000', '2026-09-18 09:00:00.000'),
       ('ORD2026091802', '熊二', '13900139000', 129.00, 'PAID', '已付款待发货', 0, '2026-09-18 09:30:00.000', '2026-09-18 10:00:00.000'),
       ('ORD2026091803', '光头强', '13700137000', 88.50, 'SHIPPED', '已发出', 0, '2026-09-18 10:15:00.000', '2026-09-18 11:00:00.000'),
       ('ORD2026091804', '李老板', '13600136000', 1288.00, 'COMPLETED', '已完成', 0, '2026-09-18 08:00:00.000', '2026-09-18 12:00:00.000'),
       ('ORD2026091805', '赵小计', '13500135000', 45.90, 'CREATED', NULL, 0, '2026-09-18 11:30:00.000', '2026-09-18 11:30:00.000');

-- 明细的外键要先查出主键。这里用会话变量而不是在 VALUES 里直接写子查询：
-- MySQL 不接受 INSERT ... VALUES 的值列表里出现 (SELECT ...) 这种写法，
-- 会报 1064 语法错误。先 SET 到变量再引用，可读性也更好。
SET @order1 = (SELECT `id` FROM `t_order` WHERE `order_no` = 'ORD2026091801');
SET @order2 = (SELECT `id` FROM `t_order` WHERE `order_no` = 'ORD2026091802');
SET @order3 = (SELECT `id` FROM `t_order` WHERE `order_no` = 'ORD2026091803');
SET @order4 = (SELECT `id` FROM `t_order` WHERE `order_no` = 'ORD2026091804');

INSERT INTO `t_order_item` (`order_id`, `product_name`, `price`, `quantity`)
VALUES (@order1, '无线鼠标', 199.00, 2),
       (@order1, '机械键盘', 201.00, 1),
       (@order2, 'USB 集线器', 129.00, 1),
       (@order3, '显示器支架', 88.50, 1),
       (@order4, '人体工学椅', 1288.00, 1);
