# 中转语音合成

使用普通 OpenAI-compatible 提供商添加或获取 CosyVoice2、MOSS-TTSD 模型，
然后在朗读设置中选择。移除单独的“新增语音合成”入口，豆包入口保留。
旧入口保存的模型、地址和凭据无需重新录入，不再依赖未持久化的 compatible_speech 类型。
保留旧导航枚举的反序列化兼容，但进入普通配置表单。

朗读设置必须以选择器解析出的 modelId（API 模型名）选择引擎和音色，不能使用数据库记录 UUID。
普通提供商的语音模型可用于朗读，仍不出现在聊天模型列表中。

先前使用的 longxiaochun_v2 和 default 在用户配置的中转返回 HTTP 400 Invalid voice。
依据 https://docs.siliconflow.cn/docs/userguide/capabilities/text-to-speech ，预设音色需带上游模型前缀。
CosyVoice2 使用 FunAudioLLM/CosyVoice2-0.5B:alex 等八个预设，
MOSS-TTSD 使用 fnlp/MOSS-TTSD-v0.5:alex 等八个预设。
完整模型名保留自己的前缀。使用既有地址和凭据、短文本“你好”分别实测两种短别名模型，
均返回 HTTP 200、audio/mpeg 且具有 MP3 文件头。凭据及生成音频不写入仓库。
不同中转是否采用相同命名仍以其接口文档为准。

回归覆盖数据库往返后进入朗读列表、UUID 与模型名分离、旧入口配置兼容、
请求发往配置的 /audio/speech 且保持模型别名与正确音色字段。
