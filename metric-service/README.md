# metric-service（RAG 质量评测旁路服务）

Python + FastAPI + RAGAS。**不进 Nacos、不注册中心**，由 ai-service 直连 HTTP 调用（讲义要求）。

## 环境

用 conda 环境 **py313**（Python 3.13），不额外建 venv：

```
D:\Python\anacondainfo\envs\py313\python.exe
```

已装：ragas 0.4.4 · fastapi 0.162 · uvicorn 0.29 · datasets 5.2 · langchain-openai · python-dotenv

## 配置

```
copy .env.example .env
# 填 LLM_API_KEY / EMBEDDING_API_KEY（当前仓库里的 .env 已配好硅基流动）
```

## 启动

```powershell
cd "D:\LearnNote\step6\Smart Knowledge Q&A\father_project\metric-service"
& "D:\Python\anacondainfo\envs\py313\python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8090
```

## 接口

| 方法 | 路径 | 说明 | 状态 |
|---|---|---|---|
| GET | `/metric/health` | 健康检查 + 已攒样本数 | ✅ 已实现 |
| POST | `/metric/sample` | 收一条线上样本 | ⬜ M4-4a |
| POST | `/metric/samples` | 批量收样本 | ⬜ M4-4b |
| POST | `/metric/eval` | 对传入样本评测（三指标，需 ground_truth） | ⬜ M4-4c |
| POST | `/metric/eval-stored` | 对已攒样本评测（两指标） | ⬜ M4-4d |
| POST | `/metric/dataset` | 从文档自动出题 | ⬜ M4-4e |
| DELETE | `/metric/samples` | 清空样本 | ⬜ M4-4f |

## 指标说明（RAG Triad）

| 指标 | 看什么 | 分低意味着 |
|---|---|---|
| faithfulness | 答案是否忠于上下文 | 幻觉，编了资料里没有的 |
| answer_relevancy | 答案是否切题 | 答非所问 |
| context_precision | 上下文是否相关（**需要参考答案**） | 召回没召回到点上 |

> ragas 0.4 已移除 `context_relevancy`，所以线上样本（无参考答案）只算前两个。

## 实现顺序（依赖决定）

```
M4-1 store.py（文件读写，最简单）
  ↓
M4-2 ragas_runner.py（真正的评测，先跑通一条样本）
  ↓
M4-3 dataset_gen.py（自动出题，独立）
  ↓
M4-4 main.py（把上面三个接成 HTTP）
```

## 验收

1. `GET /metric/health` 返回 `{"status":"ok","samples":0}`
2. `POST /metric/sample` 推一条 → `{"stored":1,"total":1}`
3. `POST /metric/eval` 传一条带 ground_truth 的样本 → 三个 0~1 的分
4. ai-service 问答一次 → `/metric/health` 的 samples +1（`metric.service.enabled=true` 已开）

## 已知限制

- 样本存 `samples.jsonl` 文件，容器化时要挂卷
- faithfulness 最慢，单批建议 ≤20 条
- 评测是同步接口，几十秒到几分钟，调用方要设足够长的超时