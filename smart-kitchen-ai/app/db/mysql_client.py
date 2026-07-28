import pymysql
from app.config import settings

def get_db_connection():
    """获取 MySQL 数据库连接"""
    return pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_pwd,
        database=settings.mysql_db,
        charset='utf8mb4',
        cursorclass=pymysql.cursors.DictCursor
    )
