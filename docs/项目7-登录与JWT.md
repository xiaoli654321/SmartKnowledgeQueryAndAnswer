# 登录与 JWT 鉴权

> 配套：[项目7-M5M6与前端.md](项目7-M5M6与前端.md)（网关 RBAC 的原始版本）
> 本文记录把"可伪造的角色头"换成真 JWT 鉴权的过程。

---

## 0. 为什么必须改

改之前，网关直接信任客户端传来的头：

```http
POST /doc/upload
X-User-Role: admin        ← 谁都能写这一行
```

前端的角色下拉框选 admin，上传就能过。**这不是鉴权，是自我声明。**

改之后：

```
客户端 → POST /auth/login  → 网关校验密码 → 签发 JWT（role 在 claim 里）
客户端 → 任意请求 + Authorization: Bearer <token>
        → 网关验签 → 取出 role → 覆盖 X-User-Role → 转发下游
```

下游服务看到的 `X-User-Role` **只可能来自网关**，客户端传的同名头会被 `headers.remove(...)` 丢掉。

---

## 1. 三个类，职责清楚

| 类 | 职责 |
|---|---|
| `UserStore` | 三个种子账号 + BCrypt 校验；对外只返回 `Principal(username, role, tenantId)`，哈希不外泄 |
| `JwtService` | 签发（subject/role/tenantId/exp）与校验；密钥来自 `security.jwt.secret` |
| `AuthController` | `POST /auth/login`、`GET /auth/me` |

### 为什么登录放在网关

入口只有一个，签发和校验在同一处，不用再起一个认证服务。真实项目里鉴权通常独立成 auth-service，但对 3 个种子账号来说这是过度设计。

### 为什么用 BCrypt 而不是明文比对

```java
private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
...
if (!ENCODER.matches(password, a.passwordHash())) return Optional.empty();
```

BCrypt 自带盐、计算慢（防彩虹表与暴力破解）。**哪怕是 demo，也不要把"明文密码"这个习惯带进代码里。**

依赖只引了 `spring-security-crypto`（做哈希），没有引 `spring-boot-starter-security` —— 后者会接管整个过滤器链，把 WebFlux 网关搞复杂。

---

## 2. 网关过滤器：先认证，再授权

```java
// 1) 放行：登录本身不能要求登录；actuator 要给 Prometheus 抓
if (path.startsWith("/actuator") || PUBLIC_PATHS.contains(path)) return chain.filter(exchange);

// 2) 解析 token（可选）：没有 token 就当 guest，但"没带"和"无效"要区分
Optional<Claims> claims = parseToken(request);
String role = claims.map(c -> String.valueOf(c.get("role"))).orElse("guest");

// 3) ★ 覆盖身份头：下游只认这个头，而它只能由网关写入
ServerHttpRequest mutated = request.mutate().headers(h -> {
    h.remove("X-User-Role");
    h.set("X-User-Role", role);
    if (claims.isPresent()) h.set("X-User-Id", claims.get().getSubject());
}).build();

// 4) 读请求 + 只读 POST 放行
if (isRead || isReadOnlyPost) return chain.filter(exchange.mutate().request(mutated).build());

// 5) 写请求：必须已登录且是 admin/editor
if (!authenticated) return reject(exchange, UNAUTHORIZED, "未登录：请先获取 token");
if (!WRITE_ROLES.contains(role)) return reject(exchange, FORBIDDEN, "无权限执行该操作");
```

### 三个状态码的语义不能混

| 情况 | 状态码 | 含义 |
|---|---|---|
| 没带 token 就要写 | **401** | 未登录 |
| token 无效 / 过期 | **401** | 身份不可信 |
| 有效 token 但角色不够 | **403** | 已登录，但没权限 |
| 密码错 | **401** | 登录失败 |

401 和 403 混用是前端最难处理的问题之一：401 该去登录，403 该提示"没权限"，不能都弹登录框。

### 只读 POST 白名单必须保留

```java
private static final Set<String> READ_ONLY_POST = Set.of("/ai/ask", "/search/hybrid");
```

`/ai/ask` 是 POST 但语义是读（不改变任何数据）。如果按"POST=写"一刀切，**未登录用户连提问都不行**，前台直接废掉。这条白名单在 M5 就有了，加 JWT 后依然需要。

---

## 3. 配置：密钥必须来自环境变量

```yaml
security:
  jwt:
    secret: ${JWT_SECRET:skqa-dev-secret-change-me-32bytes-min!!}
    ttl-seconds: 7200
```

```java
if (bytes.length < 32) {
    throw new IllegalStateException("security.jwt.secret 至少需要 32 字节（当前 " + bytes.length + "）");
}
```

- 密钥写死在代码里 = 任何人拿到代码就能伪造 token；
- 长度不足 32 字节 jjwt 会抛 `WeakKeyException`，**别去绕过它**（这是防弱密钥的保护）；
- K8s 里从 Secret 注入（`10-gateway.yaml` 的 `JWT_SECRET`），本地用默认值。

---

## 4. 前端：登录弹窗 + 自动带 token

```js
async function api(path, {method='GET', body=null, ...} = {}) {
  const headers = {};
  if (body) headers['Content-Type'] = 'application/json';
  if (getToken()) headers['Authorization'] = 'Bearer ' + getToken();   // 不再传 X-User-Role
  ...
  if (res.status === 401) { clearSession(); openLogin(); }             // 过期自动弹登录
}
```

- 顶栏原来的"角色下拉"换成了「登录 / 身份徽章 + 退出」；
- 登录弹窗两个页面共用（`ensureLoginModal()` 由 JS 注入），种子账号提示写在弹窗里；
- 会话存 `localStorage`（`skqa_token` / `skqa_user`），刷新页面自动恢复。

> 生产环境更稳的是 HttpOnly Cookie + CSRF token（避免 XSS 偷 token），demo 用 localStorage 足够。

---

## 5. 实测（8 条）

| # | 场景 | 期望 | 实际 |
|---|---|---|---|
| 1 | 密码错误 | 401 | ✅ 401 |
| 2 | admin/admin123 登录 | 200 + token | ✅ `role=admin, tenantId=1001, expiresIn=7200` |
| 3 | 无 token 上传 | 401 | ✅ `{"error":"未登录：请先获取 token（POST /auth/login）"}` |
| 4 | **伪造 `X-User-Role: admin` 但无 token** | 401 | ✅ 401（头被丢弃） |
| 5 | **guest token + 伪造 admin 头** | 403 | ✅ `{"error":"无权限执行该操作（当前角色：guest）"}` |
| 6 | admin token 上传 | 200 | ✅ `{"docId":18,...}` |
| 7 | 无 token 提问 | 200 | ✅ 200（只读 POST 白名单） |
| 8 | `GET /auth/me` | 200 + 身份 | ✅ 返回 username/role/tenantId |

经 nginx（`/api/auth/login`、`/api/doc/upload`）复测同样结果。

---

## 6. 已知局限（要能说清）

| 局限 | 影响 | 生产做法 |
|---|---|---|
| token 无法主动撤销 | 退出登录只是删本地存储，token 在 2 小时内仍有效 | 短 TTL + refresh token + 黑名单（Redis） |
| 单密钥、无轮换 | 密钥泄露需重启所有网关实例 | 支持 kid 的多密钥，灰度轮换 |
| 种子账号在内存 | 重启即恢复，无法改密 | 用户表落库 / 接 LDAP、SSO、OAuth2 |
| 没有刷新机制 | 2 小时后要重新登录 | 加 refresh token |
| 没有登录失败限流 | 可被暴力破解 | 按 IP/账号限次 + 验证码 |

---

## 7. 顺序上的一点说明

先做 JWT、后做注册是刻意的：

- **JWT 解决"你是谁可信吗"**；
- **注册解决"账号从哪来"**。

企业知识库的账号应该来自 HR/AD/LDAP/SSO，自注册等于允许任何人进内网知识库 —— 所以本项目不做自注册，只保留三个种子账号，把 `authenticate()` 换成 SSO 校验即可。
---

## 8. 登录窗口（UI）

改版后的弹窗（两页共用，`app.js` 注入）：

| 交互 | 说明 |
|---|---|
| 三个账号快捷按钮 | `admin · 管理员` / `editor · 编辑` / `guest · 访客（只读）`，点一下自动填好账号密码 |
| 密码可见切换 | 👁 / 🙈 切换 `type=password|text` |
| Enter 提交 / Esc 关闭 | 输入框内 Enter 直接登录；Esc 或点遮罩关闭 |
| 登录中状态 | 按钮变成转圈 + "登录中…"，防重复提交 |
| 错误就地显示 | 401 时在按钮上方显示"用户名或密码错误"，不弹 alert |
| 记住上次用户名 | 下次打开自动填上（密码不存） |
| 深浅色自适配 | 弹窗全部用主题变量，深色下不刺眼 |

### 后台入口

| 入口 | 位置 |
|---|---|
| 前台侧栏底部「⚙ 后台管理」 | `http://127.0.0.1:8888/` 左侧 |
| 直接地址 | `http://127.0.0.1:8888/admin.html` |
| 未登录时的顶部横幅 | 后台概览页显示"还没有登录" + 「立即登录」按钮 |

账号：`admin / admin123`（管理员）、`editor / editor123`、`guest / guest123`（只读）。

演示辅助参数：

```
?login=1     打开即弹登录框
?theme=dark  强制深色
?q=问题      自动发一条问题（仅前台）
```