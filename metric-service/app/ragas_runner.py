"""RAGAS 评测执行器（适配 ragas 0.4.x）。

三个指标的分工（对应讲义 RAG Triad）：
  faithfulness       答案是否忠于上下文 —— 分低 = 幻觉
  answer_relevancy   答案是否切题     —— 分低 = 答非所问
  context_precision  检索到的上下文是否相关 —— 分低 = 召回没召回到点上（需要参考答案）

★ 版本适配：ragas 0.4 的新 API 要求 llm_factory + InstructorLLM，
  但本机 conda 环境里 0.1 风格的 evaluate(...) + LangchainLLMWrapper 仍然可用，
  讲义用的也是这套，所以走 legacy 路径（有 DeprecationWarning，不影响结果）。
  0.4 里 context_relevancy 已被移除，无参考答案时只算 faithfulness / answer_relevancy。

★ 超时是大坑：faithfulness 要"先生成陈述、再逐条判断"，是所有指标里最慢的。
  默认 RunConfig 每任务 180s，多指标并发时它必然被挤爆，结果就是 NaN。
  所以这里显式把 timeout 拉到 300s，并把 max_retries 设为 0（重试会把超时吃满）。
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

# answer_relevancy 默认要生成 3 个问题变体（strictness=3），
# 但很多国产模型一次只返回 1 条，会被降级并刷警告，分数也偏低。设成 1 更稳、更省 token。
answer_relevancy.strictness = 1


def _judge():
    """裁判模型：temperature=0，保证同一批数据每次打分尽量一致。"""
    return LangchainLLMWrapper(
        ChatOpenAI(
            model=LLM_MODEL,
            base_url=LLM_BASE_URL,
            api_key=LLM_API_KEY,
            temperature=0,
            timeout=180,
            max_retries=0,   # 重试叠加会把 ragas 的单任务超时吃满，失败就让它失败，外层重跑
        )
    )


def _embeddings():
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

    # 0.4 的 EvaluationResult 不能用 dict()；res.scores 是"每条样本一个 dict"
    per_sample = result.scores or []
    scores = {}
    for m in metrics:
        name = m.name
        vals = []
        for row in per_sample:
            v = row.get(name)
            # 指标跑超时/解析失败时 ragas 会给 NaN，跳过它而不是把 NaN 传出去
            if isinstance(v, (int, float, float)) and not math.isnan(float(v)):
                vals.append(float(v))
        if vals:
            scores[name] = round(sum(vals) / len(vals), 4)
        else:
            scores[name] = None   # 明确告诉调用方"这个指标没算出来"，而不是假装 0 分

    log.info("评测完成 samples=%s metrics=%s", len(samples), scores)
    return scores
