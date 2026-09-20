# 个人音色试听状态与诊断

试听播放服务端 `demo_audio`，不重新发起收费合成、不修改训练音频或音色。

- 准备中即显示停止按钮，避免重复点击重建播放器。
- 只有收到 prepared 并 start 成功才进入播放中；完成/失败/主动停止后恢复播放按钮。
- 切换音色、离开声音主页、删除当前播放记录时释放播放器。
- 每次播放器使用独立代次，旧 prepared/completion/error 回调不得影响新播放。
- 选中标识包含账号和音色 ID；没有改变既有音色权限和训练流程。

`VoiceDiag kind=personal-preview` 进入现有软件日志：

- `preview.prepare/ready/start`：准备、音频时长、开始播放。
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
