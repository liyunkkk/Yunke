# 子代理网页调研工具接入

## 能力与边界

- 父运行启用网页浏览且当前权限仍允许时，所有文本子执行入口（包括工作树 implementation/review）获得受限的 `browser_use`。
- 子浏览器在单独的执行器处理；父 AgentLocalTools 的浏览器和工作树文件执行器都不会接收到子浏览器调用。
- 每次子执行拥有唯一的临时 pool，单标签、最多同时六个子 pool。超出返回 `SUB_AGENT_BROWSER_CAPACITY`，不抢占其它任务页面。池不加入主浏览器 UI/驱逐注册表；暂停期间保留，执行结束/取消时销毁。重新开始的执行需要重新导航。
- 支持 HTTP/HTTPS URL、域名、搜索词、正文提取、DOM/链接读取、滚动、截图及页面历史。点击、表单、任意脚本、Cookie 工具、下载、本地文件和外部 scheme 不开放。网页自身 JavaScript 仍用于渲染，这不是对远程网站副作用的沙箱保证。
- 禁用本地文件/Content 访问、MiniS 桥接、弹窗和自动下载；临时页不写浏览器历史或持久标签状态。没有开放 Shell、Android GUI、MCP 或递归委派。
- Android WebView 网站登录状态仍可能共享，不能把独立标签误述为独立 Cookie/账号。

## 验证记录

- 独立 Kotlin/JUnit 策略测试：5 项通过。使用生产 schema/policy/validator，模型工具 DTO 为本地测试桩，不代表整个应用编译通过。
- Python 契约/工作树回归：162 项通过，含新增接线、隔离与清理契约检查。
- 新增 SubAgentRunner 测试覆盖普通/工作树路径真实工具调用及禁用时不暴露工具；尚未通过完整 Gradle 执行。
- 本地完整 Gradle 测试被缺少 `android-37.0` SDK 阻断，未降低项目 SDK。
- 根据用户最终要求仅提交 main，不触发 GitHub 编译；尚未验证 APK 或实机并行浏览。
