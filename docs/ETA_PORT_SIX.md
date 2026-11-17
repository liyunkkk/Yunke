# Eta 六项功能移植

基线：Yunke main `3aedb39e0ade33dcc95d3b06699d232b104397b5`。
分支：`feat/eta-port-six-20261116`。
参考：Mangi-11/Eta main `9105c873b8b9f341f1482cacc5c1222480f56bf3`（获取时快照）。

## 范围

1. 文件工具：read_file/write_file/list_directory 保持原名，增加 edit_file/stat_file/glob_files/grep_files；UTF-8 字节续读、revision 校验、目录分页、Android user/root 与 Linux 后端。
2. 网页工具：web_search/fetch_url；默认公开检索与静态正文，DeepSeek Search/Fetch、自定义 HTTP 模板及 JSON 路径映射，独立设置和加密凭据。遵循原 browserTools 开关。
3. 自动标题：首轮正常结束且会话保存成功后生成；独立模型和思考档位，保留旧标题模型作为迁移回退。
4. 工具开关：逐项/整组开关、旧大类状态迁移、目录和执行边界双重检查；MCP 批量选择与刷新竞争处理。
5. 浮窗模型：已撤除新增独立配置；保留原浮窗内模型/思考选择、现有玻璃卡片、胶囊和语音模式。
6. 副屏稳定性：Root 冷启动复检；owner Hidden API 豁免、直接 WMS Binder 代理、ApplicationSharedMemory 回退、IME 策略短暂回读重试和诊断；无障碍有界查询、连接代次与迟到结果处理。保留现有 owner、帧缓存、坐标与交付机制。

## 验证

手机本地不执行 Gradle 构建。轻量验证包括 git diff --check、资源 XML 解析、Python 文件协议测试。Kotlin 编译、单元测试和 Release APK 在 GitHub Actions 执行；以 Actions 实际结论为准。

## 保留

不替换 Yunke 子代理、Antigravity、离线语音、豆包双工、现有副屏任务回收与胶囊 UI。不恢复已下线的悬浮小窗。不修改 main。

本轮已移除内置 Kimi Code 编程代理、Web 启动入口和专属配置，普通 Kimi 聊天服务商与模型保留。
