# -*- coding: utf-8 -*-
"""确保双测试账号存在(dev 库直连,仅冒烟环境用)。

账号口径:example_b / example_c(org_id=1)+ example_o(org_id=2,跨组织断言用),
密码 yingzao123(sha256)。幂等:已存在即跳过,不改密码不重置。
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

USERS = [("example_b", "示例用户B", 1), ("example_c", "示例用户C", 1),
        ("example_d", "组织一备测用户", 1), ("example_o", "组织二用户", 2)]
PASSWORD = "yingzao123"


def ensure():
    sha = hashlib.sha256(PASSWORD.encode()).hexdigest()
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            for username, display, org in USERS:
                cur.execute("select id from yz_user where username = %s", (username,))
                if cur.fetchone():
                    print("exists  %s" % username)
                    continue
                cur.execute(
                    "insert into yz_user(org_id, username, password_sha256, display_name)"
                    " values (%s, %s, %s, %s)",
                    (org, username, sha, display),
                )
                print("created %s -> id %s" % (username, cur.lastrowid))
        conn.commit()
    finally:
        conn.close()


if __name__ == "__main__":
    ensure()
