-- 运营库（biz）种子数据

DELETE FROM t_product;
DELETE FROM t_user;

INSERT INTO t_user (id, username, real_name)
VALUES (1, 'admin1', '运营库管理员'),
       (2, 'biz_op', '运营库操作员');

INSERT INTO t_product (id, product_name, category, price, stock)
VALUES (1, '机械键盘', '外设', 299.00, 120),
       (2, '显示器', '显示设备', 790.00, 35),
       (3, '鼠标垫', '外设', 43.00, 500),
       (4, '人体工学椅', '家具', 1299.00, 18);
