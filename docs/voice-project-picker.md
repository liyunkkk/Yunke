# 项目选择与声音菜单

## 项目列表

按火山 OpenAPI Explorer 的 IAM `ListProjects`（2021-08-01）接口接入。

- GET `iam.volcengineapi.com`，签名区域 `cn-beijing`、服务 `iam`。
- `Limit=100`，`Offset` 从 0 分页；读取 `Result.Projects` 和 `Result.Total`。
- 展示 `DisplayName` 和实际 `ProjectName`，保存实际项目名称；`HasPermission=false` 或缺失时不允许从列表选择。
- 只使用用户点击“获取项目列表”时提供的 AK/SK；不提交声音复刻 API Key，不创建项目，不训练、不购买。
- 最多 20 页，每页最多读取 2 MiB 响应；异常/未完整分页时不以部分列表冒充完整结果。
- 不猜测复刻 Key 所属项目、不自动选第一个；已有选择保持不变，用户点击单选项后才保存新选择。
- 读取期间禁止修改凭据、重复请求和同步名额；改变 AK/SK 会清空已取得的列表。
- 列表为空、网络/权限失败时可重试，也可展开手动填写作为备用。
- “不要发到聊天里”文案已移除。

## 菜单统一范围

“我的声音”的添加、页面设置、单条音色操作三处悬浮菜单改用共享 `EtaMaterialDropdownMenu`。
继续使用 Material 3，采用 16dp 圆角、surface 背景和细边框，阴影与色调抬升均为 0；宽度按内容计算（112–280dp），不再强制 180dp。菜单项对齐对话页的 40dp 行高与 12dp 水平内边距，统一正文字体样式。
项目选择使用 Material AlertDialog、单选行和现有点击震动。未对全应用其他业务弹窗做批量替换。

## 验证状态

新增 VoiceProjectCatalogTest，涵盖固定签名向量、分页、权限、空列表、错误/截断数据和脱敏。
代码静态检查不等于已通过编译或真实账户验证；网络调用和菜单视觉效果需在新包中验收。
