# metric-service（RAG 质量评测旁路服务）

Python + FastAPI + RAGAS。**不进 Nacos、不注册中心**，由 ai-service 直连 HTTP 调用（讲义要求）。

## 环境

用 conda 环境 **py313**（Python 3.13.13），不额外建 venv：

```
D:\Python\anacondainfo\envs\py313\python.exe
```

已装关键包：ragas 0.4.4 · fastapi 0.162.2 · uvicorn 0.29.0 · datasets 5.2.0 · langchain-openai · openai

换机器：`conda activate py313 && pip install -r requirements.txt`

## 配置

复制 `.env.example` 为 `.env`，填 LLM / Embedding 的 key 与地址（当前 .env 已配好硅基流动）。

## 启动

```powershell
cd "D:\LearnNote\step6\Smart Knowledge_Q&A\father_project\metric-service"
& "D:\Python\anacondainfo\envs\py313\python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8090
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/metric/health` | 健康检查 + 已攒样本数 |
| POST | `/metric/sample` | 收一条线上样本 `{question, contexts[], answer, ground_truth?}` |
| POST | `/metric/samples` | 批量收样本 |
| POST | `/metric/eval` | 对传入样本直接评测 `{samples:[...]}`，返回三指标 |
| POST | `/metric/eval-stored?limit=20` | 对已攒的线上样本批量评测（无参考答案→两指标） |
| POST | `/metric/dataset` | 从文档自动出题 `{documents:[...], n:3}` |
| DELETE | `/metric/samples` | 清空样本 |

## 指标说明（RAG Triad）

| 指标 | 看什么 | 分低意味着 |
|---|---|---|
| faithfulness | 答案是否忠于上下文 | 幻觉，编了资料里没有的 |
| answer_relevancy | 答案是否切题 | 答非所问 |
| context_precision | 检索上下文是否相关（**需要参考答案**） | 召回没召回到点上 |

> ragas 0.4 已移除 `context_relevancy`，所以线上样本（没有参考答案）只算前两个。

## 已知限制

- 样本存 `samples.jsonl`（文件），不是数据库；容器化时要挂卷
- faithfulness 最慢，单批建议 ≤20 条；超时参数见 `app/ragas_runner.py` 的 `RunConfig`
- 评测是同步接口，几秒到几分钟，调用方要设足够长的超时