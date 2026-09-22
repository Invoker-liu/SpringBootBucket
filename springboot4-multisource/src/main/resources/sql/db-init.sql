-- 建库脚本：本篇需要两个库。
-- 刻意让两个库完全独立（不共用），因为演示的就是「两个不相干的库怎么在同一个进程里共存」。

DROP DATABASE IF EXISTS springboot4_pos;
CREATE DATABASE springboot4_pos DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

DROP DATABASE IF EXISTS springboot4_biz;
CREATE DATABASE springboot4_biz DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
