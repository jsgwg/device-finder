# -*- coding: utf-8 -*-
"""Device Finder 服务端（国内版）：设备注册、位置上报、指令队列、JPush 下发、Web 面板。"""
import os
import sqlite3
import time

import requests
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

BASE = os.path.dirname(__file__)
DB_PATH = os.path.join(BASE, "devices.db")
JPUSH_CFG = os.path.join(BASE, "jpush.json")  # {"app_key":"...","master_secret":"..."}

JPUSH_API = "https://api.jpush.cn/v3/push"


def load_jpush():
    """读取极光配置；未配置时下发接口降级为仅指令队列。"""
    import json
    if os.path.exists(JPUSH_CFG):
        with open(JPUSH_CFG, encoding="utf-8") as f:
            cfg = json.load(f)
        return cfg.get("app_key"), cfg.get("master_secret")
    return None, None


def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.executescript(
        """CREATE TABLE IF NOT EXISTS devices(
            device_id TEXT PRIMARY KEY,
            model TEXT,
            lat REAL, lng REAL, acc REAL, battery INTEGER,
            last_seen INTEGER);
        CREATE TABLE IF NOT EXISTS commands(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT, action TEXT,
            created INTEGER, consumed INTEGER DEFAULT 0);"""
    )
    return conn


class RegisterBody(BaseModel):
    device_id: str
    model: str = ""


class ReportBody(BaseModel):
    device_id: str
    lat: float
    lng: float
    acc: float = 0
    battery: int = -1


app = FastAPI(title="Device Finder (CN)")


@app.post("/api/register")
def register(b: RegisterBody):
    with db() as conn:
        conn.execute(
            "INSERT INTO devices(device_id,model) VALUES(?,?) "
            "ON CONFLICT(device_id) DO UPDATE SET model=excluded.model",
            (b.device_id, b.model),
        )
    return {"ok": True}


@app.post("/api/report")
def report(b: ReportBody):
    with db() as conn:
        cur = conn.execute(
            "UPDATE devices SET lat=?, lng=?, acc=?, battery=?, last_seen=? WHERE device_id=?",
            (b.lat, b.lng, b.acc, b.battery, int(time.time()), b.device_id),
        )
        if cur.rowcount == 0:
            raise HTTPException(404, "device not registered")
        # 上报即视为消费了定位指令
        conn.execute(
            "UPDATE commands SET consumed=1 WHERE device_id=? AND action='locate'", (b.device_id,)
        )
    return {"ok": True}


@app.get("/api/devices")
def devices():
    with db() as conn:
        rows = conn.execute(
            "SELECT device_id, model, lat, lng, acc, battery, last_seen "
            "FROM devices ORDER BY last_seen DESC"
        ).fetchall()
    return [dict(r) for r in rows]


@app.post("/api/locate/{device_id}")
def locate(device_id: str):
    """下发定位：写指令队列（15 分钟内轮询兜底）+ 尝试 JPush 秒级触达。"""
    with db() as conn:
        dev = conn.execute("SELECT 1 FROM devices WHERE device_id=?", (device_id,)).fetchone()
        if dev is None:
            raise HTTPException(404, "device not found")
        conn.execute(
            "INSERT INTO commands(device_id,action,created) VALUES(?, 'locate', ?)",
            (device_id, int(time.time())),
        )

    pushed = False
    push_error = None
    app_key, master_secret = load_jpush()
    if app_key and master_secret:
        try:
            resp = requests.post(
                JPUSH_API,
                json={
                    "platform": "all",
                    "audience": {"alias": [device_id]},
                    # 自定义消息：通知栏不显示，客户端 JPushReceiver.onMessage 处理
                    "message": {"msg_content": "locate", "extras": {"action": "locate"}},
                    "options": {"time_to_live": 300, "apns_production": True},
                },
                auth=(app_key, master_secret),
                timeout=10,
            )
            if resp.status_code != 200:
                push_error = resp.text[:200]
            else:
                pushed = True
        except Exception as e:
            push_error = str(e)
    else:
        push_error = "jpush.json 未配置，仅靠设备 15 分钟轮询消费指令"

    return {"queued": True, "pushed": pushed, "push_error": push_error}


@app.get("/api/pending/{device_id}")
def pending(device_id: str):
    """设备轮询接口：取未消费指令并标记消费，返回是否需要定位。"""
    with db() as conn:
        row = conn.execute(
            "SELECT COUNT(*) AS n FROM commands WHERE device_id=? AND action='locate' AND consumed=0",
            (device_id,),
        ).fetchone()
        conn.execute(
            "UPDATE commands SET consumed=1 WHERE device_id=? AND action='locate' AND consumed=0",
            (device_id,),
        )
    return {"has_pending": row["n"] > 0, "locate": row["n"] > 0}


@app.get("/")
def panel():
    return FileResponse(os.path.join(BASE, "index.html"))
