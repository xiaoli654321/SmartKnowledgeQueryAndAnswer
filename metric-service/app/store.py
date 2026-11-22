"""样本存储：追加写 JSONL。

为什么用文件而不是数据库：
- 评测是旁路服务，讲义明确它不建库、不注册 Nacos；
- JSONL 追加写最简单，出问题还能直接打开看；
- 真生产换成 Kafka / 对象存储，但接口语义（攒样本、批量评）不变。

并发：uvicorn 默认单进程，但多个请求可能交错写文件，
所以用 RLock 包住写操作；用 RLock 而不是 Lock，是因为 append 里还会调 count()。
"""
from __future__ import annotations

import json
import threading
from typing import List

from .config import SAMPLE_FILE
from .schemas import Sample

_lock = threading.RLock()


def append(sample: Sample) -> int:
    """追加一条样本，返回当前总条数。"""
    with _lock:
        with open(SAMPLE_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps(sample.model_dump(), ensure_ascii=False) + "\n")
        return count()


def append_many(samples: List[Sample]) -> int:
    """批量追加，返回当前总条数。"""
    with _lock:
        with open(SAMPLE_FILE, "a", encoding="utf-8") as f:
            for s in samples:
                f.write(json.dumps(s.model_dump(), ensure_ascii=False) + "\n")
        return count()


def count() -> int:
    """统计行数；文件不存在时返回 0（首次运行就是这种状态）。"""
    try:
        with open(SAMPLE_FILE, "r", encoding="utf-8") as f:
            return sum(1 for _ in f)
    except FileNotFoundError:
        return 0


def load(limit: int = 200) -> List[Sample]:
    """读最近的 limit 条；空行和坏行跳过，不让一条脏数据毁掉整批评测。"""
    try:
        with open(SAMPLE_FILE, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except FileNotFoundError:
        return []

    out: List[Sample] = []
    for line in lines[-limit:]:
        line = line.strip()
        if not line:
            continue
        try:
            out.append(Sample(**json.loads(line)))
        except Exception:
            continue
    return out


def clear() -> None:
    """清空样本（DELETE 接口用）。"""
    with _lock:
        open(SAMPLE_FILE, "w", encoding="utf-8").close()