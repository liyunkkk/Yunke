# Responses 正文重复展示

2026-09-20 实机会话“测试回复”：conversation_messages 中同一 run/round 有两个 assistant 块（索引 1 和 2），正文均为“收到，测试正常。有什么需要帮忙的随时说。”；conversation_context_checkpoints 中只有一条 assistant 及一份正文。不是两个独立运行。已完成任务的原始 SSE 与事件没有保留，因此无法从本机记录确认具体是 item_id、output_index 还是 content_index 差异。

Responses 终态 reconcileFinalPart 原先完全依赖内容块身份匹配，匹配失败会为终态正文再次创建 BlockStart/Delta。兼容接口在流式与终态使用不同 ID 或索引时，可以构造出与截图相同的重复路径。

新增有限回退：仅在本轮有一个流式正文块、终态有一个正文段，且终态原文包含相同流式前缀时，复用原块并应用终态正文。多个真实段落仍按身份匹配，不按文字去重，避免吞掉合法重复内容。不会修改已有会话数据库。

新增测试覆盖终态 ID 改写、缺 ID 的 output_index 变化、已结束的部分正文补全，以及合法重复段落保留。本地 Gradle 测试因 Android SDK 路径未配置而未启动；后续由 CI 验证。现有重复消息保留，不能将这项代码修改视为旧记录已清理或实际 SSE 差异已确认。
