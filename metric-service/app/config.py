"""配置：全部走环境变量 / .env，不写死在代码里。

为什么不写死 key：一旦提交，几分钟内就会被扫走刷额度；换模型也不用改代码。
（这份是基建，给全，不用你改）
"""
import os

from dotenv import load_dotenv

load_dotenv()

LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.siliconflow.cn/v1")
LLM_MODEL = os.getenv("LLM_MODEL", "zai-org/GLM-5.3-Flash")

EMBEDDING_API_KEY = os.getenv("EMBEDDING_API_KEY", LLM_API_KEY)
EMBEDDING_BASE_URL = os.getenv("EMBEDDING_BASE_URL", LLM_BASE_URL)
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")

# 样本落盘（评测是旁路服务，讲义明确"不建库"，用文件最简单）
SAMPLE_FILE = os.getenv("SAMPLE_FILE", "samples.jsonl")