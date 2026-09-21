# 生图：按真实协议组装请求

直接聊天和图片子代理共用 `AgentImageGenerationClient`。不按模型名称或中转品牌猜测协议。

## 默认行为的改变

参考 Imagine 的“先确定目标像素尺寸，再发送 Images 请求”流程：未指定私有配置时，
`9:16 + 2k` 转成 `size: "1152x2048"`；`1:2 + 2k` 转成 `size: "1024x2048"`。
不再默认发送 `aspect_ratio` 和 `resolution`，也不把 9:16 换成 1024x1536（后者是 2:3）。

这是 **Eta 的目标长边约定**，不是任何提供商原生 2K 档位的保证。支持 1k/1.5k/2k/3k/4k，
长边分别为 1024/1536/2048/3072/4096；比例和档位必须同时明确。不能算成整数像素时报错，
不做参考项目的 64 像素吸附，也不偷偷换比例。服务端仍可能拒绝任意 WxH；服务端拒绝就显示错误。
只要求比例、不指定档位时，应直接指定 size 或在 sizes 中配置该比例的尺寸。

精确 `size: "1920x2560"` 直接保留。不自动裁剪、插值放大、换端点或重发。

### 本次参数

```text
画一只猫，9:16，2k
```

复杂描述用独立 JSON 行（实际发送不含代码围栏）：

```text
画一只猫
image_options: {"aspect_ratio":"9:16","resolution":"2k"}
```

子代理通过 `delegate_task.image_options` 传参；`task` 只写画面描述，不让生图模型“报告尺寸/校验结果”，
避免把诊断说明画进图片。参数优先级：配置默认值 < 描述中的明确参数 < 本次结构化参数。

## 实际支持的传输协议

| 路径 | 协议 | 边界 |
| --- | --- | --- |
| `/images/generations` | Images JSON 文生图 | 默认发送 size，支持显式字段映射 |
| `/images/edits` | multipart 编辑 | 保留多张参考图；遮罩须与唯一参考图同尺寸 PNG |
| `/images/generations` | `generations_image` JSON 编辑扩展 | image/mask 是 data URL；仅单张参考图；服务端是否理解 mask 由端点契约决定 |
| `/images/edits` | `json_image_url` 编辑扩展 | image 是 `{type:"image_url",url:...}`；单图、无遮罩 |
| 原生完整地址，根地址补 `/ai/generate-image` | NovelAI 原生文生图 | input/action/parameters；接收图片或 ZIP；不支持原生图生图/遮罩 |

Chat Completions、Responses、Anthropic Messages 是独立文本/视觉链路，不能自动替代生图协议。
移除了原来在 Images 404/405/501 后自动改走 Chat 的行为。Sub2API/NewAPI 等只表示网关，
不能据名称保证其转发了哪些参数。没有虚构 OAIRes 专属生图协议。

## 私有配置位置

在提供商或模型的自定义请求体中添加 **JSON 对象** `eta_image_config`。
无需更改模型名单；直接聊天和子代理共用配置。内部对象不会进入 Images 或文本请求。
现阶段使用已有自定义请求体编辑器配置；没有新增专用协议选择 UI。

### 原生比例/分辨率字段透传（已有配置迁移）

原先依赖默认 `aspect_ratio/resolution` 的端点，需要显式选择：

```json
{"eta_image_config":{"protocol":"passthrough"}}
```

此时 9:16/2k 仍发送原始 `aspect_ratio/resolution`。这只是端点契约声明，不保证任何型号支持。
默认编辑保持 multipart，原有 `json_image_url` 配置继续有效。

### 字段与取值映射

若你的接口文档明确要求 ratio + size 档位：

```json
{
  "eta_image_config": {
    "protocol": "passthrough",
    "fields": {"aspect_ratio":"ratio", "resolution":"size"},
    "values": {"resolution":{"1k":"1K","2k":"2K","4k":"4K"}}
  }
}
```

请求最终只含 `ratio:"9:16"`、`size:"2K"`；不会再发旧的几何字段。
values 未覆盖本次取值或多个有效参数映射到同一字段时，本地拒绝，不能默默覆盖。
字段支持最多六层对象路径（例如 `generationConfig.imageConfig.aspectRatio`），
但字段映射**不代表已支持其他厂商的原生 URL、认证方式或响应协议**。

### 精确尺寸映射

```json
{
  "eta_image_config": {
    "protocol":"size",
    "sizes":{"9:16":"864x1536", "9:16@2k":"1152x2048"}
  }
}
```

`size` 协议只用显式表，缺项报错；`size_long_edge` 协议优先用表，否则按目标长边精确计算。
两者都校验映射尺寸与用户比例一致，不允许把 9:16 映射为 2:3。

### JSON 图生图扩展

```json
{"eta_image_config":{"edit_protocol":"generations_image"}}
```

仅在有参考图时选择该编辑路径；文生图仍使用 `/images/generations`。
不会把多张图片拼成一张，也不会丢掉额外图片或不支持的遮罩。
当前新增的 mask 参数是底层客户端能力；没有新增涂抹遮罩界面或子代理附件传入入口。

### NovelAI 原生

```json
{
  "eta_image_config": {
    "endpoint":"novelai_native",
    "native_parameters": {
      "params_version":3, "steps":28, "scale":5,
      "sampler":"k_euler_ancestral", "noise_schedule":"native",
      "cfg_rescale":0, "negative_prompt":"", "seed":1234
    }
  }
}
```

基础地址填站点根或完整原生端点，使用已配置的 Bearer Key。
params_version、sampler 等按该模型/端点文档明确配置，不按模型名自动猜版本。
原生参数保留，实际 prompt、width/height、n_samples 由本次需求设置；v4/v5 模板的 base_caption
同步当前 prompt，保留角色字段。必须给精确尺寸或完整比例/档位，也可在 native_parameters 提供 width/height 默认值。
宽高 64–2048 且为 64 倍数，单次 1–4 张；缺少 seed 时生成随机种子。原生 quality/response_format 不支持则明确拒绝。
ZIP 限制压缩/解压总量、单项大小、条目数与图片数，不把服务端文件名解压到磁盘，检查取消状态。

默认 Anthropic 配置会在联网前说明不支持；若中转确实另有 Images 端点，可显式
`"endpoint":"openai_images"`，使用 Images URL 与 Bearer 鉴权，不把消息协议误认成生图。

## 校验与验证范围

结果检查解码的实际宽高和数量，保留原始文件。不符合目标就报告 `IMAGE_DIMENSIONS_MISMATCH`；
无法解码则 `IMAGE_DIMENSIONS_UNVERIFIED`。请求成功不代表档位或比例生效。
测试使用本地拦截器/固定二进制，不调用计费生成接口；真实中转是否完整实现该协议仍需单独验证。

## 参考

参考仓库 `lzhhhhc/imagine`，检查提交 `dc6d5bef11aba94323112f5429ffb3d83ac6e341`。
重点参考 ApiModels、ImageRepository、CreativePresets、NaiNativeClient 的协议组织。
Eta 独立实现，不移植其 UI、角色工作台、自动重试、合图与缩放裁剪逻辑。
参考项目保留放大选项且默认关闭，不能把“有 ensureResolution”说成默认会改图。
来源说明和 MIT 许可见 `third_party/imagine-reference/`。
