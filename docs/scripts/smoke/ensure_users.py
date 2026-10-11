# -*- coding: utf-8 -*-
"""确保双测试账号存在(dev 库直连,仅冒烟环境用)。

账号口径:example_b / example_c,密码 yingzao123(sha256),org_id=1。
幂等:已存在即跳过,不改密码不重置。
"""
import hashlib
import os

import pymysql

DB = dict(
    host=os.environ.get("YINGZAO_DB_HOST", "192.168.5.21"),
    port=int(os.environ.get("YINGZAO_DB_PORT", "3306")),
    database=os.environ.get("YINGZAO_DB_NAME", "yingzao"),
    user=os.environ.get("YINGZAO_DB_USER", "root"),
    password=os.environ.get("YINGZAO_DB_PASSWORD", "Neusoft!"),
    charset="utf8mb4",
)

USERS = [("example_b", "示例用户B"), ("example_c", "示例用户C")]
PASSWORD = "yingzao123"


def ensure():
    sha = hashlib.sha256(PASSWORD.encode()).hexdigest()
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            for username, display in USERS:
                cur.execute("select id from yz_user where username = %s", (username,))
                if cur.fetchone():
                    print("exists  %s" % username)
                    continue
                cur.execute(
                    "insert into yz_user(org_id, username, password_sha256, display_name)"
                    " values (1, %s, %s, %s)",
                    (username, sha, display),
                )
                print("created %s -> id %s" % (username, cur.lastrowid))
        conn.commit()
    finally:
        conn.close()


if __name__ == "__main__":
    ensure()
