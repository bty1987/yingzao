# -*- coding: utf-8 -*-
# 第113次:施工域三方向初稿 lint 红灯批量修正(11px->12px / 句子12->14 / ink35->ink62 / 赭金加深)
import io, re

BASE = r"D:/ERP/yingzao/docs/design/"

def patch(fname, subs):
    p = BASE + fname
    s = io.open(p, encoding="utf-8").read()
    for old, new in subs:
        if old not in s:
            print("  MISS:", fname, repr(old[:60]))
        s = s.replace(old, new)
    io.open(p, "w", encoding="utf-8").write(s)
    print("patched", fname)

common = [
    ("font-size:11px", "font-size:12px"),
    ("font-size: 11px", "font-size: 12px"),
    (".tok{color:rgba(250,249,245,.55);font-size:12px", ".tok{color:rgba(250,249,245,.55);font-size:14px"),
    (".board-head .en{color:rgba(250,249,245,.62);font-size:12px", ".board-head .en{color:rgba(250,249,245,.62);font-size:14px"),
]

d1 = [
    # 对比度:信息性 ink35 -> ink62(占位符除外)
    (".node-no{font-family:'Songti SC','SimSun',serif;font-size:15px;color:var(--ink35)", ".node-no{font-family:'Songti SC','SimSun',serif;font-size:15px;color:var(--ink62)"),
    (".st-wait{color:var(--ink35)}", ".st-wait{color:var(--ink62)}"),
    (".plan .ph span{font-family:'Songti SC','SimSun',serif;font-size:13px;color:var(--ink35)", ".plan .ph span{font-family:'Songti SC','SimSun',serif;font-size:13px;color:var(--ink62)"),
    ("color:var(--ink35);font-size:22px", "color:var(--ink62);font-size:22px"),
    (".dr-time{font-size:12px;color:var(--ink35)", ".dr-time{font-size:13px;color:var(--ink62)"),
    # 字号:标签 12->13,句子 12->14
    (".node-plan{font-size:12px", ".node-plan{font-size:13px"),
    (".pro-no{font-size:12px", ".pro-no{font-size:13px"),
    (".bc-no{font-size:12px", ".bc-no{font-size:13px"),
    (".bc-days{font-size:12px", ".bc-days{font-size:13px"),
    (".bar-cap{display:flex;justify-content:space-between;font-size:12px", ".bar-cap{display:flex;justify-content:space-between;font-size:13px"),
    (".crew{font-size:12px", ".crew{font-size:13px"),
    (".kv .v .mini{font-size:12px", ".kv .v .mini{font-size:13px"),
    (".plan-note{font-size:12px", ".plan-note{font-size:14px"),
    (".sheet .hint{font-size:12px", ".sheet .hint{font-size:14px"),
    # 赭金加深
    (".dr-type.out{background:rgba(166,134,75,.14);color:var(--ochre)}", ".dr-type.out{background:rgba(166,134,75,.14);color:#7A5E2A}"),
]

d2 = [
    (".sub{color:var(--ink62);font-size:12px", ".sub{color:var(--ink62);font-size:14px"),
    (".ledg .c1{width:26px;font-family:'Songti SC','SimSun',serif;font-size:13px;color:var(--ink35)", ".ledg .c1{width:26px;font-family:'Songti SC','SimSun',serif;font-size:13px;color:var(--ink62)"),
    (".nums .k{font-size:12px", ".nums .k{font-size:13px"),
    (".pcap{display:flex;justify-content:space-between;font-size:12px", ".pcap{display:flex;justify-content:space-between;font-size:13px"),
    (".d-time{font-size:11px;color:var(--ink35)", ".d-time{font-size:12px;color:var(--ink62)"),
    (".plan .th span{font-size:11px;color:var(--ink35)", ".plan .th span{font-size:12px;color:var(--ink62)"),
    (".arrow{color:var(--ink35)", ".arrow{color:var(--ink62)"),
    (".tag-warning{background:rgba(166,134,75,.14);color:var(--ochre)}", ".tag-warning{background:rgba(166,134,75,.14);color:#7A5E2A}"),
    (".upcell{width:50px;height:50px;border:1px dashed var(--line-strong);border-radius:3px;display:flex;align-items:center;justify-content:center;color:var(--ink35)", ".upcell{width:50px;height:50px;border:1px dashed var(--line-strong);border-radius:3px;display:flex;align-items:center;justify-content:center;color:var(--ink62)"),
]

d3 = [
    (".ttime{font-size:11px;color:var(--ink35)", ".ttime{font-size:12px;color:var(--ink62)"),
    (".jd .t{font-size:11px;color:var(--ink35)", ".jd .t{font-size:12px;color:var(--ink62)"),
    (".tl .tdesc{font-size:12px", ".tl .tdesc{font-size:13px"),
    (".tcrew{font-size:12px", ".tcrew{font-size:13px"),
    (".d-nums .k{font-size:12px", ".d-nums .k{font-size:13px"),
    (".roll .no{font-size:12px", ".roll .no{font-size:13px"),
    (".slip .s-node{font-size:12px", ".slip .s-node{font-size:13px"),
    (".upcell{width:54px;height:54px;border:1px dashed var(--line-strong);display:flex;align-items:center;justify-content:center;color:var(--ink35)", ".upcell{width:54px;height:54px;border:1px dashed var(--line-strong);display:flex;align-items:center;justify-content:center;color:var(--ink62)"),
    (".ab .ph span{font-family:'Songti SC','SimSun',serif;font-size:12px;color:var(--ink35)", ".ab .ph span{font-family:'Songti SC','SimSun',serif;font-size:12px;color:var(--ink62)"),
    (".jd .tp.out{background:rgba(166,134,75,.14);color:var(--ochre)}", ".jd .tp.out{background:rgba(166,134,75,.14);color:#7A5E2A}"),
]

patch("construction-dir-1.html", common + d1)
patch("construction-dir-2.html", common + d2)
patch("construction-dir-3.html", common + d3)
print("done")
