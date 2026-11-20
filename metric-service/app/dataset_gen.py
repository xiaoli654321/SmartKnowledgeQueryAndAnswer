"""自动出题：让 LLM 从文档里出问答对，省掉人工标注。

讲义原话："自动出题的本质是用 LLM 造评测 LLM 的数据"。
注意 prompt 里要强制它输出**纯 JSON 数组**，并做容错解析 —— 模型经常在 JSON 外面
裹一层 ```json 代码块，生产上一定要剥掉再 parse。
"""
import json
import logging
import re
from typing import List

from openai import OpenAI

from .config import LLM_API_KEY, LLM_BASE_URL, LLM_MODEL

log = logging.getLogger("metric.dataset")

_client = OpenAI(api_key=LLM_API_KEY, base_url=LLM_BASE_URL)

_SYSTEM = (
    "你是企业知识库的测试工程师。根据用户给出的文档生成问答对，"
    "严格输出 JSON 数组，形如 [{\"question\":\"...\",\"answer\":\"...\"}]，不要任何解释文字。"
)


def generate_qa_pairs(documents: List[str], n: int = 3) -> List[dict]:
    """每篇文档生成 n 个问答对；答案必须来自文档内容。"""
    pairs: List[dict] = []
    for doc in documents:
        try:
            resp = _client.chat.completions.create(
                model=LLM_MODEL,
                messages=[
                    {"role": "system", "content": _SYSTEM},
                    {"role": "user", "content": f"文档：\n{doc}\n\n请生成 {n} 个问答对，答案必须能在文档中找到依据。"},
                ],
                temperature=0.3,
            )
            content = resp.choices[0].message.content or ""
            pairs.extend(_parse_json_array(content))
        except Exception as e:
            log.error("出题失败：%s", e)
    return pairs


def _parse_json_array(text: str) -> List[dict]:
    """容错解析：剥掉 ```json 包裹、截取第一个 [ 到最后一个 ]。"""
    text = text.strip()
    text = re.sub(r"^```(?:json)?", "", text).strip()
    text = re.sub(r"```$", "", text).strip()
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end <= start:
        log.warning("未找到 JSON 数组，跳过：%s", text[:80])
        return []
    try:
        data = json.loads(text[start:end + 1])
    except json.JSONDecodeError as e:
        log.warning("JSON 解析失败：%s；原文：%s", e, text[:120])
        return []
    return [x for x in data if isinstance(x, dict) and "question" in x and "answer" in x]