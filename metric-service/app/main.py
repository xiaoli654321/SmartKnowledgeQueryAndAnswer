r"""metric-service：RAG 质量评测服务（Python + RAGAS）。

定位（讲义原话）：离线/批处理/评测类【旁路服务】，不进 Nacos、不注册中心，Java 侧直连 HTTP。
数据流：ai-service 每次问答 @Async 推一条样本到这里 → 攒到一定量 → 批量跑 RAGAS 打分。

启动（用 conda 的 py313 环境）：
    D:\Python\anacondainfo\envs\py313\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8090
"""
import logging

from fastapi import FastAPI, HTTPException

from . import store
from .config import EMBEDDING_MODEL, LLM_MODEL
from .dataset_gen import generate_qa_pairs
from .ragas_runner import evaluate_samples
from .schemas import DatasetRequest, EvalRequest, EvalResponse, Sample, SampleResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("metric")

app = FastAPI(title="metric-service", description="RAGAS 评测旁路服务", version="1.0.0")


@app.get("/metric/health")
def health():
    return {
        "status": "ok",
        "judge_model": LLM_MODEL,
        "embedding_model": EMBEDDING_MODEL,
        "samples": store.count(),
    }


@app.post("/metric/sample", response_model=SampleResponse)
def add_sample(sample: Sample):
    """接收一条线上问答样本（ai-service 异步推送）。"""
    total = store.append(sample)
    return SampleResponse(stored=1, total=total)


@app.post("/metric/samples", response_model=SampleResponse)
def add_samples(samples: list[Sample]):
    total = store.append_many(samples)
    return SampleResponse(stored=len(samples), total=total)


@app.post("/metric/eval", response_model=EvalResponse)
def evaluate(req: EvalRequest):
    """对传入的样本直接评测（同步，会调裁判模型，几十秒级）。"""
    if not req.samples:
        raise HTTPException(status_code=400, detail="samples 不能为空")
    has_gt = all(s.ground_truth for s in req.samples)
    try:
        scores = evaluate_samples(req.samples)
    except Exception as e:
        log.exception("评测失败")
        raise HTTPException(status_code=500, detail=f"评测失败：{e}")
    return EvalResponse(metrics=scores, count=len(req.samples), used_ground_truth=has_gt)


@app.post("/metric/eval-stored")
def evaluate_stored(limit: int = 20):
    """对已攒的线上样本批量评测。

    线上样本没有参考答案，所以只有 faithfulness / answer_relevancy
    （ragas 0.4 已移除 context_relevancy，context_precision 必须有参考答案）。
    """
    samples = store.load(limit=limit)
    if not samples:
        raise HTTPException(status_code=400, detail="还没有样本，先让 ai-service 推几条过来")
    try:
        scores = evaluate_samples(samples)
    except Exception as e:
        log.exception("评测失败")
        raise HTTPException(status_code=500, detail=f"评测失败：{e}")
    return {"metrics": scores, "count": len(samples)}


@app.post("/metric/dataset")
def make_dataset(req: DatasetRequest):
    """从文档自动生成评测数据集（问题 + 参考答案）。"""
    if not req.documents:
        raise HTTPException(status_code=400, detail="documents 不能为空")
    pairs = generate_qa_pairs(req.documents, n=req.n)
    return {"count": len(pairs), "pairs": pairs}


@app.delete("/metric/samples")
def clear_samples():
    store.clear()
    return {"cleared": True}