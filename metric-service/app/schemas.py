"""请求/响应模型：FastAPI 靠它做校验和自动文档。"""
from typing import List, Optional
from pydantic import BaseModel, Field


class Sample(BaseModel):
    """一条评测样本：问题 + 检索到的上下文 + 模型答案 +（可选）参考答案。

    四个字段正好对应 RAGAS 三个指标的输入：
      faithfulness       需要 answer + contexts
      answer_relevancy   需要 question + answer
      context_precision  需要 contexts + ground_truth
    """
    question: str
    contexts: List[str] = Field(default_factory=list)
    answer: str
    ground_truth: Optional[str] = None
    tenantId: Optional[int] = None


class EvalRequest(BaseModel):
    samples: List[Sample]


class SampleResponse(BaseModel):
    stored: int
    total: int


class EvalResponse(BaseModel):
    metrics: dict
    count: int
    used_ground_truth: bool


class DatasetRequest(BaseModel):
    documents: List[str]
    n: int = 3