"""RAGAS 评测执行器（ragas 0.4.x）。

三个指标（RAG Triad）：
  faithfulness       答案是否忠于上下文 —— 分低 = 幻觉
  answer_relevancy   答案是否切题     —— 分低 = 答非所问
  context_precision  上下文是否相关   —— 分低 = 召回没召回到点上（需要参考答案）

三个版本坑（都是实测出来的）：
1) context_relevancy 在 0.4 已移除 → 无参考答案时只能算前两个；
2) 必须显式给 RunConfig(timeout=300)：默认每任务 180s，faithfulness 最慢会被挤爆成 NaN；
   max_retries 设 0，否则重试会把超时吃满（实测 183s+NaN → 54s+1.0）；
3) 结果不能用 dict(result)（KeyError: 0），要用 result.scores。
"""
import logging
import math
import warnings
from typing import List

from datasets import Dataset
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from ragas import RunConfig, evaluate
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import answer_relevancy, context_precision, faithfulness

from .config import (
    EMBEDDING_API_KEY,
    EMBEDDING_BASE_URL,
    EMBEDDING_MODEL,
    LLM_API_KEY,
    LLM_BASE_URL,
    LLM_MODEL,
)
from .schemas import Sample

warnings.filterwarnings("ignore", category=DeprecationWarning)

log = logging.getLogger("metric.ragas")

# answer_relevancy 默认生成 3 个问题变体，国产模型常只回 1 条，
# 会被降级并刷警告、分数偏低。设成 1 更稳也更省 token。
answer_relevancy.strictness = 1


def _judge() -> LangchainLLMWrapper:
    """裁判模型：temperature=0，同一批数据每次打分尽量一致。"""
    return LangchainLLMWrapper(
        ChatOpenAI(
            model=LLM_MODEL,
            base_url=LLM_BASE_URL,
            api_key=LLM_API_KEY,
            temperature=0,
            timeout=180,
            # 1 次重试：网络抖动（APIConnectionError）是真实存在的，不重试会让指标直接变 NaN；
            # 但也不能设 2 次以上 —— 重试叠加会把 ragas 的单任务超时（300s）吃满。
            max_retries=1,
        )
    )


def _embeddings() -> LangchainEmbeddingsWrapper:
    """向量模型：answer_relevancy 用它算语义相似度。"""
    return LangchainEmbeddingsWrapper(
        OpenAIEmbeddings(
            model=EMBEDDING_MODEL,
            base_url=EMBEDDING_BASE_URL,
            api_key=EMBEDDING_API_KEY,
        )
    )


def evaluate_samples(samples: List[Sample]) -> dict:
    """对一批样本打分，返回 {指标: 平均分(0~1)}。"""
    if not samples:
        return {}

    has_gt = all(s.ground_truth for s in samples)
    metrics = [faithfulness, answer_relevancy]
    if has_gt:
        metrics.append(context_precision)

    data = {
        "question": [s.question for s in samples],
        "contexts": [s.contexts for s in samples],
        "answer": [s.answer for s in samples],
    }
    if has_gt:
        data["ground_truth"] = [s.ground_truth for s in samples]

    ds = Dataset.from_dict(data)
    result = evaluate(
        ds,
        metrics=metrics,
        llm=_judge(),
        embeddings=_embeddings(),
        show_progress=False,
        run_config=RunConfig(timeout=300, max_workers=2),
    )

    # 0.4 的 EvaluationResult 是"每条样本一个 dict"的列表
    per_sample = result.scores or []
    scores = {}
    for m in metrics:
        name = m.name
        vals = []
        for row in per_sample:
            v = row.get(name)
            # 指标跑超时/解析失败时 ragas 会给 NaN，跳过而不是把 NaN 传出去
            if isinstance(v, (int, float)) and not math.isnan(float(v)):
                vals.append(float(v))
        scores[name] = round(sum(vals) / len(vals), 4) if vals else None

    log.info("评测完成 samples=%s metrics=%s", len(samples), scores)
    return scores