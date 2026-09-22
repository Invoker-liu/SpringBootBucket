-- 种子数据。由 spring.sql.init.data-locations 在启动时执行，每次启动都跑一遍。
--
-- 为什么是 DELETE + INSERT 而不是 INSERT ... ON DUPLICATE KEY UPDATE：
-- 前者能把余额恢复成固定初值，后者做不到「把被改过的值改回去还要把上一条 INSERT 的 id 用回来」。
-- 这个工程的每个演示接口都会真的改余额，所以每次启动必须回到同一个起点，
-- 否则截出来的图和正文里的数字对不上（这是前几篇踩过的坑）。
--
-- id 显式写死，不依赖 AUTO_INCREMENT，这样 accountId=1 永远是熊大。

DELETE FROM t_account;
DELETE FROM t_tx_event;
ALTER TABLE t_account AUTO_INCREMENT = 1;
ALTER TABLE t_tx_event AUTO_INCREMENT = 1;

INSERT INTO t_account (id, owner, balance, version, updated_at)
VALUES (1, '熊大', 1000.00, 0, NOW(3)),
       (2, '熊二', 500.00, 0, NOW(3)),
       (3, '熊三', 300.00, 0, NOW(3)),
       (4, '熊四', 200.00, 0, NOW(3));
