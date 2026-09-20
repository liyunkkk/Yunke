# MiMo 参考音色

“我的声音”分为豆包语音、MiMo 语音两个 Material 卡片入口。豆包沿用音色名额流程；MiMo 保存本机参考录音，不创建或轮询云端训练任务。

MiMo 使用现有提供商配置，导入 MP3/WAV，按文件头和系统媒体元数据验证，Base64 编码后上限 10,000,000 字节。保存声音名、提供商 ID、类型、时长和应用私有音频；不保存 API Key。参考音频每次合成发送至绑定提供商，界面有明确说明。

试听使用独立参数，不改变朗读配置；“用于朗读”才写入朗读偏好。普通朗读选择器仍只显示 mimo-v2.5-tts，选择本机个人音色时底层切换 mimo-v2.5-tts-voiceclone。样本缺失、提供商失效时失败，不替换为公共音色。

POST /v1/chat/completions，assistant content 为朗读文字，audio.voice 为 data URI，audio.format=wav，stream=false。解码 choices[0].message.audio.data，验证 WAV，不重复封装 PCM。复用统一播放器的取消、麦克风互斥、音频焦点、文件清理。音频及 Base64 不写入诊断日志。

参考：https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5
