# Anthropic 提示词缓存

Eta 的原生 Anthropic Messages 适配器默认请求服务端提示词前缀缓存，不是在手机保存答案，也不是保证每次命中。旧版已能解析缓存 usage，但没有主动发送 `cache_control`；第三方网关也没有补标记时不会主动申请这类缓存。

## 默认请求

默认控制块是 `{"type":"ephemeral","ttl":"1h"}`，请求 1 小时 TTL。上游或网关必须支持该 TTL；不支持时可显式改用 `5m` 或 `none`。适配器把 system 文本保留为内容块数组，并在最终合并的请求上最多添加四个显式断点：

1. 最后一个工具定义（覆盖工具前缀）。
2. 主提示构造器标记的指令边界（在记忆和 Skills 注入之前）。
3. 最后一个可缓存 system 块（覆盖完整 system；与第 2 项相同时去重）。
4. 最后一条 user 消息的最终可缓存内容块，包括规范化合并后的 tool_result 批次。

对话历史、工具顺序不重排；不在 thinking、空文本或 assistant prefill 上放默认消息断点。主提示构造器的 `eta_prompt_cache_boundary` 是本地 metadata，由适配器消费，不进入 HTTP 请求。自定义 system 覆盖不会沿用生成提示的边界索引。

断点在 customBody 合并、reasoning 参数处理和最终消息配对校验之后加入，因此针对的是实际序列化正文，不是内部未转换的 OpenAI 消息形状。断点不能消除它之前的内容变化；工具 schema、前面的运行约束或动态系统状态变化，以及压缩重写历史，仍可能降低命中率。

## 自定义控制

在“提供商 → 配置 → 自定义请求体”中添加字段：字段名填 `eta_prompt_cache`，值填 JSON 字符串（包括双引号），例如 `"1h"`。不配置时也默认使用 1 小时，**不要填在“自定义请求头”或“会话 ID → 网关”里**。该设置属于请求体字段，与网关会话键 `prompt_cache_key` 不同。

供应商/模型自定义请求体可加入本地字段 `eta_prompt_cache`，值只接受：

| 值 | 行为 |
| --- | --- |
| `5m` | 显式切换为 5 分钟；所有默认断点省略 `ttl` |
| `1h` | 默认策略；所有默认断点添加 `ttl: "1h"`，须网关/上游支持，缓存写入价格更高 |
| `none` | 不添加默认断点，用于不兼容网关 |

该本地字段会在序列化前移除。无需配置即可启用默认 1 小时缓存；不按供应商显示名 `Claude Max` 推定 OAuth 订阅或改变缓存策略。

如果最终请求已经含有顶层或合法工具/system/message 内容块上的 `cache_control`，调用者拥有整套缓存策略，适配器不再添加默认断点，避免重复超限或混用 TTL。`none` 只禁止 Eta 自动添加标记，**不会删除调用者显式提供的标记**；要彻底禁止，应同时移除自定义标记。工具参数 schema 内同名属性、tool_result 数据内同名键不算缓存断点。

请求体编辑器支持添加、编辑和删除字段，保存时检查空字段名、重复字段名和 JSON 值。`true`、`123`、`null`、对象和数组保持对应 JSON 类型；`"true"`、`"123"` 保持字符串类型。输入不合法时阻止保存和连接测试；未完成的编辑内容会作为 UI 草稿保留，不写入供应商配置。

## 观测与限制

既有 usage 解析保持不变：

- `cache_creation_input_tokens`：缓存写入；首次请求可能只有写入，无读取。
- `cache_read_input_tokens`：缓存读取；大于 0 才表示真实命中。
- 总输入为未缓存输入、缓存创建和缓存读取之和；不能把写入算成命中。

实际效果仍取决于网关保留缓存控制及原始 usage、上游模型最低长度、相同模型/前缀、有效期和服务端隔离策略。默认缓存请求不等于已验证网关缓存生效。缓存是否命中须以实际 usage 为准；静态检查或单元测试不能替代真实上游验证，不自动发起付费验证请求或更新安装在设备上的 APK。

## 参考实现和验证范围

- Anthropic 官方 API 文档：https://platform.claude.com/docs/en/build-with-claude/prompt-caching
- Claude Code 官方文档：https://code.claude.com/docs/en/prompt-caching
- `deepseek-ai/deepseek-harness` 调查版本：`5badb15009ae1756c3afe0ae0cef1faafc290ccc`。
- 其 `packages/llm/llm-pi-ai/src/adapter.ts` 将 `cacheRetention` 传给 `@earendil-works/pi-ai`；该版本依赖 0.87.1。已只读核对其发布包 `dist/api/anthropic-messages.js`：默认 short，在非 OAuth system、最后一个工具以及最终 user 内容块放显式缓存断点。这里借鉴的是 API Key 路径，不复制 OAuth 身份伪装。
- Harness 的 `SystemPromptProjection` 在路线支持时把变化的 system 追加到历史，维护稳定前缀；该特性依赖路线能力，不能直接强行套用到所有 Anthropic 网关。Eta 本次采用独立的稳定指令断点，不新增中途 system 协议。
- Harness 的 `request-cache.e2e.ts` 是需要 DeepSeek API Key 的真实缓存测试，不能把它当成 Claude 缓存验证。本次没有执行它。

已新增缓存请求回归测试源码，覆盖默认断点、四断点上限/去重、稳定指令边界、工具结果合并、关闭、默认 1h 和显式 5m/1h、自定义缓存策略、schema 同名键、空文本/思考/pre-fill、幂等和非法本地设置，并在现有 HTTP 流测试中断言请求标记。另有请求体 JSON 类型与提供商草稿/保存回归测试。按用户要求本地不编译，GitHub Actions 的构建工作流负责运行单元测试与 Release 编译；是否通过应以对应提交的工作流结论为准。
