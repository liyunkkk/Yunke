# 个人音色试听状态与诊断

试听播放服务端 `demo_audio`，不重新发起收费合成、不修改训练音频或音色。

- 准备中即显示停止按钮，避免重复点击重建播放器。
- 只有收到 prepared 并 start 成功才进入播放中；完成/失败/主动停止后恢复播放按钮。
- 切换音色、离开声音主页、删除当前播放记录时释放播放器。
- 每次播放器使用独立代次，旧 prepared/completion/error 回调不得影响新播放。
- 选中标识包含账号和音色 ID；没有改变既有音色权限和训练流程。

`VoiceDiag kind=personal-preview` 进入现有软件日志：

- `preview.prepare/ready/start`：准备、音频时长、开始播放。
- `preview.download/downloaded/download_error`：远端 demo 下载开始、落盘字节数、下载失败。
- `preview.buffer`：缓冲百分比，限频记录。
- `preview.complete`：自然结束时的位置、音频时长及 early 标记（已知时长且相差超过 500ms）。
- `preview.error`：MediaPlayer what/extra 错误码；-1 表示初始化/准备异常，-2 表示 start 异常。
- `preview.stop`：停止原因、当时位置、时长。reason 0 离开页面、1 点击停止、2 切换、3 完成、4 错误、5 删除。

不记录音频 URL、音色 ID、账户标识、正文或音频。early 仅辅助排查，不是音频损坏的证明。若播放器报告播放到文件末尾而听感仍截句，需继续核对服务端试听文件本身；当前改动不能证明该现象已经根治。

## 提前完成回调的释放保护

实机两次日志均为 durationMs=3407、positionMs=2731、early=1，buffer=100，reason=3；没有错误或用户停止记录。
自然结束回调后不立即 release，按剩余位置差（最多 2000ms）加 150ms 留出输出尾部缓冲时间。
等待期间继续显示停止按钮；主动停止、切换、离开页面立即取消等待并释放，旧任务不能影响新播放。
新增 preview.drain / preview.drained 事件。此修正针对立即释放可能截断输出尾部的问题，仍需实机确认，不能据日志断言服务端文件完整。

## 先下载并规范化 WAV 后再播放

豆包 demo 音频是 ffmpeg 流式写法：WAV 的 `RIFF`（offset 4）与 `data` chunk 的 size 都是 `0xFFFFFFFF`。
Android 的 WAV 解析器（`libwavextractor.so`）必须信任这两个长度字段，因此把远端 https URL 直接交给
MediaPlayer 流式播放会在 prepare 阶段异步失败（`preview.error what=1`），表现为「试听播放失败」。

现在的试听流程：

1. 先用 OkHttp（连接 10s / 读取 30s）把 demo 下载到 `cacheDir/voice-preview/` 临时文件；
2. 下载后走 `normalizeWavLengths`：`data` 长度若为 `0xFFFFFFFF` 或超过剩余字节，改写为真实长度；
   `data` 不是最后一个 chunk 时截断到 data 末尾；`RIFF` 总长改写为 `最终文件长度 − 8`；
   非 WAV（MP3 等）原样返回；
3. `setDataSource(本地路径)` + `prepareAsync()`，不再走网络栈。

其它约束：

- 下载是异步的，完成后会再校验代次与播放器归属，切音色/离开页面的旧下载会被丢弃；
- 下载期间 `loading=true`（界面显示「正在加载试听…」）；
- 下载失败与播放失败分开文案；
- 临时文件在完成/失败/切换/离开页面时删除。

**注意**：demo URL 自带 `x-expires`（约 2 小时）。过期后旧地址会失效，需要先重新拉取音色状态
拿到新的 `demo_audio` 地址再试听，不能长期复用同一地址。

