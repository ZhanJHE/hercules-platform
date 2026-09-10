-- Canal 专用账号（幂等，阶段 B）：伪装 MySQL 从库读取 binlog
-- 注意：docker-entrypoint-initdb.d 仅在数据卷首次初始化时执行；
--       存量 mysql-data 卷需手动执行一次（语句幂等，可重复运行）。
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'canal';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
