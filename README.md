# 小红书话题研究助手

一个本地运行的 Java 21 Demo：输入关键词后，系统通过 CDP 连接你人工登录的 Chrome，采集本次搜索返回的候选帖子，选择相关热门 Top 20，读取详情和图片文字，生成中文研究报告，并支持 5W1H、上下文追问、增量比较和 Agent API 复用。

> 本项目刻意采用低成本的本地方案：不需要云服务器、域名或数据库。数据保存在 Markdown 文件中。

## 1. 技术栈

- Java 21
- Spring Boot 3.3 / Spring MVC / Thymeleaf
- SSE 实时进度与报告流
- Playwright Java Over CDP（禁用 Playwright 默认浏览器下载，只连接用户手动启动的 CDP Chrome）
- OpenAI-compatible Chat Completions
- Anthropic Messages API
- springdoc-openapi
- Markdown + fenced JSON 持久化

## 2. 安装要求

- JDK 21
- Chrome 或 Chromium
- Maven 3.9，或使用仓库内 Maven wrapper
- OpenAI 或 Anthropic API Key

不需要安装 IDEA；任意编辑器和终端均可运行。

## 3. 启动步骤

### 3.1 配置模型

```bash
cp config/application-local.example.yml config/application-local.yml
```

项目支持两类协议：

1. **Anthropic Messages API**
2. **OpenAI-compatible Chat Completions API**

大部分主流国产模型平台都提供 OpenAI-compatible 接口，因此复用同一个可配置客户端，不需要为每家平台引入私有 SDK。支持下列 provider 别名：

| 平台 | provider |
|---|---|
| OpenAI / 任意兼容网关 | `openai`、`openai-compatible`、`generic` |
| 智谱 GLM | `glm`、`zhipu`、`bigmodel` |
| DeepSeek | `deepseek` |
| Kimi / Moonshot | `moonshot`、`kimi` |
| 通义千问 / DashScope | `qwen`、`dashscope`、`tongyi` |
| 豆包 / 火山方舟 | `doubao`、`ark`、`volcengine` |
| MiniMax | `minimax` |
| SiliconFlow | `siliconflow` |

### GLM 示例

```yaml
assistant:
  model:
    provider: glm
    openai:
      base-url: https://open.bigmodel.cn/api/paas/v4
      api-key: "your-glm-api-key"
      chat-model: glm-4-plus
      vision-model: glm-4v-plus
```

### DeepSeek 示例

```yaml
assistant:
  model:
    provider: deepseek
    openai:
      base-url: https://api.deepseek.com/v1
      api-key: "your-deepseek-api-key"
      chat-model: deepseek-chat
      # DeepSeek API 主要是文本模型；图片 OCR 需要换成平台实际可用的视觉模型。
      vision-model: replace-with-available-vision-model
```

### Kimi / Moonshot 示例

```yaml
assistant:
  model:
    provider: moonshot
    openai:
      base-url: https://api.moonshot.cn/v1
      api-key: "your-moonshot-api-key"
      chat-model: moonshot-v1-8k
      vision-model: moonshot-v1-8k-vision-preview
```

### Qwen / DashScope 示例

```yaml
assistant:
  model:
    provider: qwen
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key: "your-dashscope-api-key"
      chat-model: qwen-plus
      vision-model: qwen-vl-plus
```

### 豆包 / 火山方舟示例

```yaml
assistant:
  model:
    provider: doubao
    openai:
      base-url: https://ark.cn-beijing.volces.com/api/v3
      api-key: "your-ark-api-key"
      # Ark 可能使用接入点 ID 或模型 ID，以控制台为准。
      chat-model: your-endpoint-or-model-id
      vision-model: your-vision-endpoint-or-model-id
```

### Anthropic 示例

```yaml
assistant:
  model:
    provider: anthropic
    anthropic:
      base-url: https://api.anthropic.com
      api-key: "sk-ant-..."
      api-version: 2023-06-01
      chat-model: claude-3-5-sonnet-latest
      vision-model: claude-3-5-sonnet-latest
```

模型名会随平台更新而变化。`chat-model` 负责文本、相关性和报告；`vision-model` 必须支持图片输入，用于图片 OCR。若无可用视觉模型，图片 OCR 会失败并在覆盖范围中如实标注。

如果要严格禁止模型失败时降级为离线报告：

```yaml
assistant:
  model:
    allow-offline-fallback: false
```

### 3.2 启动并登录浏览器

```bash
./scripts/start-browser.sh
```

脚本会创建独立 profile：

```text
.local/xhs-browser-profile
```

请不要使用日常主 profile。打开的浏览器中手动登录小红书；如果出现验证码，只人工处理，系统不会绕过。

### 3.3 启动服务

```bash
./scripts/run.sh
```

然后访问：

```text
http://127.0.0.1:8080
```

健康检查：

```text
http://127.0.0.1:8080/actuator/health
```

OpenAPI：

```text
http://127.0.0.1:8080/swagger-ui.html
```

## 4. 使用流程

1. 在页面输入任意关键词，例如 `Manus 2`。
2. 点击“开始研究”。
3. 系统自动执行：
   - 排队
   - 外层搜索
   - 候选清洗与去重
   - 相关性判断
   - 按点赞降序排序
   - 选择 Top 20
   - 逐个读取详情页
   - 图片 OCR
   - Evidence Pack
   - 报告 Plan
   - 有界 Agent Loop
   - 流式报告生成
   - Final Answer Gate
4. 页面实时展示进度、事件、指标和报告 token。
5. 展开帖子可懒生成 5W1H。
6. 任务完成后可在“上下文追问”提问，服务端只使用当前任务材料。
7. 点击“再次运行”创建增量子任务，比较新增帖子、热度变化和新观点。

不需要用户逐步点击“下一步”。只有登录失效、验证码、用户取消或需要人工选择恢复时才介入。

## 5. 采集口径

- 每次任务最多读取搜索返回前 100 个不同帖子作为候选池。
- 先做相关性和版本歧义判断，再按点赞降序选择最多 20 条。
- 只对 Top 20 逐个读取详情页。
- 图片 OCR 只处理入选帖子，每帖最多 6 张。
- 视频音画不处理，会在覆盖范围中明确标注。
- 搜索结果里的 `xsec_token`、`xsec_source` 会随详情 URL 一起保存；没有 `xsec_token` 时不会直接访问详情页。
- 详情页出现 `300031`、“当前笔记暂时无法浏览”或“请打开小红书 App 扫码查看”时，立即停止后续详情采集，并把该帖子记录为访问受限。
- 结果表述为“本次搜索范围内”，不是“小红书全站最热”。
- 点赞显示值保留原文，例如 `1.2万`，同时保存换算值和 `APPROXIMATE` 精度。
- 缺失点赞不当作 0，排序时排在有值帖子之后。

## 6. 保守采集策略

- CDP 浏览器全局单 worker 串行执行。
- 不使用 headless。
- 不自动登录、不保存密码、不导出 Cookie。
- 不做代理池、多账号或分布式采集。
- 页面操作有随机等待。
- 详情页访问有较长随机间隔。
- 登录失效或验证码会暂停为 `WAITING_USER`，等待人工处理。
- 单帖详情失败会记录并继续，不无限重试。
- 页面加载最多 2 次重试；模型超时、429、5xx 使用指数退避。

平台风控风险无法降为零。如果连续出现验证码或明显权限异常，请停止任务并保留已有结果。

## 7. 数据存储

默认目录：

```text
data/
  index.md
  tasks/{taskId}/
    task.md
    events.md
    candidates.md
    posts/P001.md
    report.md
    diff.md
    metrics.md
    qa/Q0001.md
  cache/posts/{postId}.md
  cache/images/
  attachments/
```

结构化数据放在 Markdown 的 fenced JSON 中，机器读取 JSON，人可以直接检查 Markdown。写入使用临时文件加原子替换。可修改：

```yaml
assistant:
  storage:
    root: ./data
```

## 8. 任务恢复

- 每个阶段完成后写入 checkpoint。
- 帖子以 `postId` 幂等，图片以 hash/序号幂等，报告以 `taskId` 幂等。
- Worker 定期写 heartbeat 和 lease。
- 服务重启时扫描 `task.md`，运行中任务标记 `INTERRUPTED` 并重新排队。
- 已成功的详情、OCR、5W1H 和报告不会重复生成。
- 如果整个 Java 进程退出，进程内无法继续推送 SSE；重启后从 Markdown 恢复。

## 9. Agent API

创建任务：

```bash
curl -X POST http://127.0.0.1:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"keyword":"Manus 2","runMode":"NEW"}'
```

返回 `202`：

```json
{
  "taskId": "...",
  "statusUrl": "/api/tasks/...",
  "streamUrl": "/api/tasks/.../stream",
  "resultUrl": "/api/tasks/.../result"
}
```

查询状态：

```bash
curl http://127.0.0.1:8080/api/tasks/{taskId}
```

获取结果：

```bash
curl http://127.0.0.1:8080/api/tasks/{taskId}/result
```

订阅 SSE：

```bash
curl -N http://127.0.0.1:8080/api/tasks/{taskId}/stream
```

主要接口：

```text
POST   /api/tasks
GET    /api/tasks
GET    /api/tasks/{taskId}
GET    /api/tasks/{taskId}/stream
GET    /api/tasks/{taskId}/result
POST   /api/tasks/{taskId}/retry
POST   /api/tasks/{taskId}/cancel
POST   /api/tasks/{taskId}/rerun
GET    /api/tasks/{taskId}/diff

POST   /api/tasks/{taskId}/posts/{postRef}/5w1h
GET    /api/tasks/{taskId}/posts/{postRef}/5w1h
GET    /api/tasks/{taskId}/posts/{postRef}/5w1h/status

POST   /api/tasks/{taskId}/questions
GET    /api/tasks/{taskId}/questions
GET    /api/tasks/{taskId}/questions/{questionId}
GET    /api/tasks/{taskId}/metrics
```

外部 Agent 不需要逐步推进流水线；创建任务后轮询或订阅，完成后读取结果即可。

## 10. 报告 Agent 设计

报告阶段不是开放式自主浏览器 Agent。浏览器采集和 OCR 完成后，报告 Agent 只使用当前任务的结构化材料：

1. 构建每帖 Evidence Pack。
2. 一次轻量 Plan 调用。
3. 有界内部检索，最多 8 轮。
4. 每轮工具调用写入 trace。
5. 流式生成报告。
6. 服务端 Final Answer Gate 检查：
   - 引用有效
   - 章节完整
   - 范围表述正确
   - 指标精度保留
   - 关键结论有证据
7. 未通过最多两次定向修复。
8. trace、模型用量和 gate 结果写入 `metrics.md` / `report.md`。

## 11. 已知限制

- 小红书页面结构可能变化；DOM 选择器集中在 `XhsCollector`，如失效会如实返回错误。
- 视频语音和画面不处理。
- 图片 OCR 依赖多模态模型；图片模糊或遮挡时质量会下降。
- 没有数据库和消息队列，本方案面向单机 Demo，不适合高并发生产。
- 单浏览器 worker 会让多个任务排队，这是有意设计，用于降低账号风险。
- 离线 fallback 只保证流程可演示，报告质量有限；正式运行建议关闭 fallback。

## 12. 开发与测试

```bash
mvn test
mvn package
```

运行打包产物：

```bash
java -jar target/xhs-topic-assistant-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --spring.config.additional-location=optional:file:./config/
```

## 13. 安全提醒

- 服务默认只绑定 `127.0.0.1`。
- 不要提交 `config/application-local.yml`。
- 不要提交 `data/`，其中可能包含页面材料和任务记录。
- 不要把 API Key 写入 README、截图或演示日志。
