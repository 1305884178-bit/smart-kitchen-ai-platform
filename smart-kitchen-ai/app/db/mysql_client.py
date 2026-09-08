import pymysql
from dbutils.pooled_db import PooledDB
from app.config import settings

# MySQL 小连接池：Python 侧只读为主（销量/评价/菜名/知识库指纹/清理比对），
# 池上限保持个位数（默认 5），避免与 Java Hikari（maximum-pool-size 20）抢死 MySQL。
# 生产环境应为 Python 侧配置只读账号（DB_USER 环境变量，见 .env.example）。
_pool = PooledDB(
    creator=pymysql,
    maxconnections=settings.db_pool_size,
    mincached=1,
    maxcached=settings.db_pool_size,
    blocking=True,  # 池满时等待而不是抛错
    ping=1,         # 取连接时探测存活，失效自动重建
    host=settings.mysql_host,
    port=settings.mysql_port,
    user=settings.mysql_user,
    password=settings.mysql_pwd,
    database=settings.mysql_db,
    charset='utf8mb4',
    cursorclass=pymysql.cursors.DictCursor,
    autocommit=True,  # 只读场景为主；显式写需自行 commit 的调用方不受影响
)


def get_db_connection():
    """从连接池获取 MySQL 连接；调用方 close() 仅归还连接池而非真正关闭。"""
    return _pool.connection()
