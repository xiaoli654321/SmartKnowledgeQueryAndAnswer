# 问题修复记录（2026-11-22 晚）

> 配套：[项目7-设计与实施文档.md](项目7-设计与实施文档.md)
> 本文记录 M0–M7 完成后的自查结果与修复，按严重程度排列。

---

## 修复 1：CI 一跑就红（P1）

### 现象

四个模块的测试类都是裸的：

```java
@SpringBootTest
class DocServiceApplicationTests {
    @Test void contextLoads() { }
}
```

`contextLoads` 会真的启动 Spring 上下文 → 连 Nacos → **注册失败 fail-fast 把上下文拉崩**。
CI 里没有注册中心，所以 `mvn verify` 必红（本地同样可以复现：`Client not connected` → `Application run failed`）。

### 修复

```java
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class DocServiceApplicationTests { ... }
```

Kafka / MySQL / Redis 的客户端都是懒连接，不连不影响上下文启动。

### 验证

```
mvn -o verify
Tests run: 1, Failures: 0, Errors: 0   （gateway / doc / search / ai 四个都过）
BUILD SUCCESS
```

---

## 修复 2：检索结果全是同一篇文档的重复片段（P1）

### 现象

同一份制度文档被切成 2 块，"年假"类问题会让这两块同时占据前 2 名，
答案里的 `[1][2]` 指向几乎一样的文字 —— 看起来"依据充分"，实际是同义反复。
库里还堆了 4 篇内容完全一样的文档（测试时反复上传），问题被放大成 5 条出处。

### 修复一：按文档收敛

`HybridSearchService.fuse()` 在截断到 `limit` 之前，先按 `documentId` 计数：

```java
@Value("${search.max-chunks-per-doc:2}")
private int maxChunksPerDoc;

// 融合排序之后
Map<Long, Integer> perDoc = new HashMap<>();
for (Merge m : list) {
    int used = perDoc.getOrDefault(m.documentId, 0);
    if (used >= maxChunksPerDoc) continue;
    perDoc.put(m.documentId, used + 1);
    out.add(new SearchHit(...));
    if (out.size() >= limit) break;
}
```

配置：`search.max-chunks-per-doc: 2`。

### 修复二：清理重复文档

```sql
DELETE FROM search_db.doc_chunk      WHERE document_id IN (14,15,16);
DELETE FROM doc_db.kb_document_version WHERE document_id IN (14,15,16);
DELETE FROM doc_db.kb_document       WHERE id IN (14,15,16);
```

### 验证

```
查询：年假有几天，出差住宿标准是多少   topK=5
返回 3 条：doc17×1、doc13×2      ← 每篇文档最多 2 块，且跨文档
问答出处：[1] doc=13 chunk=0  [2] doc=13 chunk=1  [3] doc=17 chunk=0
```

出处现在真的代表"两个不同依据"。

---

## 修复 3：后台缺"文档列表"接口（P2）

### 现象

后台只能按 ID 查文档，管理员不知道库里有什么。

### 修复

`doc-service` 补 `GET /doc?tenantId=&limit=`，三层都加：

```java
// DocService
List<KbDocument> list(Long tenantId, int limit);

// DocServiceImpl：强制带 tenant_id，id 倒序（最新在前），limit 夹在 1~100
return docMapper.selectList(new QueryWrapper<KbDocument>()
        .eq("tenant_id", tenantId)
        .orderByDesc("id")
        .last("limit " + Math.max(1, Math.min(limit, 100))));

// DocController：注意是 GET /doc（无路径变量），与 GET /doc/{id} 不冲突
@GetMapping
@RequirePermission("document:read")
public List<KbDocument> list(@RequestParam("tenantId") Long tenantId,
                             @RequestParam(value = "limit", defaultValue = "20") int limit)
```

后台页面加了「列出文档」按钮，走 `/api/doc?tenantId=...`。

### 验证

```
GET /doc?tenantId=1001  →  #17 差旅报销制度 / #13 年假政策
经 nginx：/api/doc?tenantId=1001 → 同样结果
```

---

## 过程中踩到的两个操作坑（踩坑也是为了下次快）

| 现象 | 原因 | 处理 |
|---|---|---|
| `mvn package` 报 `Unable to rename ... .jar.original` | 旧的 java 进程还在跑，jar 被占用 | 先按端口找 PID 杀掉，再 `mvn clean package` |
| 同一个服务出现两个 java 进程 | 上一次重启时旧进程没死，端口被新进程抢到 | 用 `CommandLine -match '<服务名>'` 全量清理，别只按端口杀 |

---

## 仍然存在的问题（未修，按需）

| 问题 | 说明 |
|---|---|
| API key 在公开仓库历史里 | 已知风险，用户接受（额度很低）；换 key 只需改 3 处 |
| RBAC 可被前端伪造 | `X-User-Role` 由页面自己选，真实项目必须网关解析 JWT 并覆盖该头 |
| Text2SQL 未实现 | 讲义九项能力之一，当前四个服务里没有 |
| 无业务单测 | 只有 4 个 `contextLoads`，改坏了没人拦 |
| K8s 清单未真跑 | 无集群，只做了结构与语法校验 |
---

## 修复 4：出处里塞了 4 条不相关的内容（P1）

### 现象

加了 6 篇「冷知识」文档后提问，答案本身是对的，但每次都返回 5 条出处：

```
问：章鱼为什么血是蓝色的？
答：……血蓝蛋白…… [1]
出处：[1] doc21 [2] doc25 [3] doc23 [4] doc21 [5] doc20   ← 后 4 条与问题无关
```

### 原因

检索固定返回 topK 条（凑满 5 条），而答案通常只用到其中 1~2 条。
把 hits 原样映射成 citations，等于把"检索候选"当成"引用依据"展示。

这不是信息更多，而是误导——用户会以为系统引用了 5 段材料，实际上只用了 1 段。

### 修复

`RagService` 里用正则提取答案中真正出现的 `[n]`，只保留被引用的那几条：

```java
private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)\\]");
...
Set<Integer> cited = new LinkedHashSet<>();
Matcher m = CITATION_PATTERN.matcher(reply.content());
while (m.find()) cited.add(Integer.parseInt(m.group(1)));

for (int i = 0; i < hits.size(); i++) {
    int index = i + 1;
    if (!cited.isEmpty() && !cited.contains(index)) continue;   // 没被引用就不返回
    ... new Citation(index, ...)                                 // 保留原编号，不重排
}
```

两个细节：

| 细节 | 为什么 |
|---|---|
| **保留原始编号，不重新排序** | 否则 citations 里的 index 与答案里的 `[n]` 对不上，点开的片段就是错的那段 |
| **一条都没引用时回退前 2 条** | 模型偶尔忘了标出处，宁可给"最可能的两条"也不要返回空列表 |

### 验证

| 问题 | 改前 | 改后 |
|---|---|---|
| 章鱼为什么血是蓝色的？ | 5 条（4 条无关） | **1 条** `[1] doc21` |
| 蜂蜜为什么不会变质？ | 5 条 | **1 条** `[1] doc25` |
| 章鱼几颗心脏？蜂蜜放三千年还能吃吗？ | 5 条 | **2 条** `[1] doc21 / [2] doc25` |
| 太空里真的有味道吗？ | 5 条 | **2 条** `[1][2] doc23`（同一文档两块） |

多文档问题仍然会给出多条出处，但每一条都是答案真正引用过的。