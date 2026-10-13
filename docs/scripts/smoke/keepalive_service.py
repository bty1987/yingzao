# -*- coding: utf-8 -*-
"""冒烟窗口守护:8080 无监听即拉起 yingzao jar(对抗宿主机不定期 java 清杀,第 25 次实锤)。

用法:python keepalive_service.py   (后台;收尾必须 taskkill 该 python + 8080 持有者)
纪律:ENV 变量名对齐 application.yml relaxed binding(YINGZAO_EVENTS_*),第 47 次修正
      旧版错名导致守护拉起的服务用默认 60s 事件退避、冒烟对账超时的坑。
"""
import os
import subprocess
import time
import urllib.error
import urllib.request

JAR = r"D:\ERP\yingzao\yingzao-app\target\yingzao-app-0.1.0-SNAPSHOT.jar"
ENV = {
    **os.environ,
    "YINGZAO_DB_URL": "jdbc:mysql://192.168.5.21:3306/yingzao?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
    "YINGZAO_DB_USER": "root",
    "YINGZAO_DB_PASSWORD": "Neusoft!",
    # 事件总线快退避五件套(relaxed binding)
    "YINGZAO_EVENTS_BACKOFFSECONDS": "1", "YINGZAO_EVENTS_BACKOFFCAPSECONDS": "5",
    "YINGZAO_EVENTS_MAXRETRIES": "2", "YINGZAO_EVENTS_POLLINTERVALMILLIS": "2000",
    "YINGZAO_EVENTS_POLLINITIALDELAYMILLIS": "3000",
}


def up():
    try:
        with urllib.request.urlopen("http://127.0.0.1:8080/api/auth/login", data=b"{}", timeout=4):
            return True
    except urllib.error.HTTPError:
        return True  # 端口活着(4xx/5xx 也算服务在)
    except Exception:
        return False


print("keepalive start", flush=True)
while True:
    if not up():
        print(time.strftime("%H:%M:%S"), "service down, relaunching", flush=True)
        subprocess.Popen(
            ["java", "-jar", JAR],
            env=ENV, cwd=r"D:\ERP\yingzao",
            stdout=open(r"D:\tmp\yingzao_keepalive.log", "ab"),
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NEW_PROCESS_GROUP,
        )
        for _ in range(60):
            time.sleep(2)
            if up():
                print(time.strftime("%H:%M:%S"), "service back", flush=True)
                break
    time.sleep(5)
