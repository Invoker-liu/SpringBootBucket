-- 交易库（pos）种子数据
--
-- 主键全部写死、脚本先删后插，目的是让每次启动后的查询结果完全可复现。
-- 文章正文里的响应体是从真实运行结果抄的，数据每次变就没法核对了。

DELETE FROM t_order_item;
DELETE FROM t_order;
DELETE FROM t_user;

INSERT INTO t_user (id, username, real_name)
VALUES (1, 'admin', '交易库管理员'),
       (2, 'pos_op', '交易库操作员');

INSERT INTO t_order (id, order_no, customer_name, total_amount, status, created_at, version)
VALUES (1, 'POS20260901001', '周伯通', 299.00, 'PAID', '2026-09-01 10:00:00.000', 0),
       (2, 'POS20260902002', '黄药师', 1580.00, 'CREATED', '2026-09-02 11:20:00.000', 0),
       (3, 'POS20260903003', '洪七公', 86.00, 'CANCELLED', '2026-09-03 15:45:00.000', 0);

INSERT INTO t_order_item (id, order_id, product_name, quantity, price)
VALUES (1, 1, '机械键盘', 1, 299.00),
       (2, 2, '显示器', 2, 790.00),
       (3, 3, '鼠标垫', 2, 43.00);
