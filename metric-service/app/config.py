"""配置集中管理：全部从环境变量读，缺省指向硅基流动。

为什么不用 config.py 里写死 key：
- 仓库一旦提交，key 就泄了（GitHub 上被抓到几分钟就会被扫走刷额度）；
- 换供应商/换模型要改代码，等于每次都得改文件重启。
.env 文件放 key，代码里只读 os.getenv。
"""
import os
from dotenv import load_dotenv

# 同时支持项目根 .env 和 app/.env
load_dotenv()

LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.siliconflow.cn/v1")
LLM_MODEL = os.getenv("LLM_MODEL", "zai-org/GLM-5.3-Flash")

EMBEDDING_API_KEY = os.getenv("EMBEDDING_API_KEY", LLM_API_KEY)
EMBEDDING_BASE_URL = os.getenv("EMBEDDING_BASE_URL", LLM_BASE_URL)
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")

# 样本落盘路径（评测服务不建库，讲义要求"不入 Nacos、无库"，用文件最简单）
SAMPLE_FILE = os.getenv("SAMPLE_FILE", "samples.jsonl")

# SkyWalking OTLP 上报开关（M6 用）
OTEL_ENABLED = os.getenv("OTEL_ENABLED", "false").lower() == "true"
OTEL_ENDPOINT = os.getenv("OTEL_ENDPOINT", "127.0.0.1:11800")