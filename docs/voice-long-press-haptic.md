# 录音按钮长按触感

录音入口使用 Compose `combinedClickable`。系统组件默认会在长按阈值触发一次触感，业务回调此前又调用 `TouchHaptics.longPress`，因此部分设备会出现两段震动。

录音按钮及识别指示器现在关闭 `combinedClickable` 的内建触感，只在业务长按回调入口调用一次 `TouchHaptics.longPress`。普通点击仍保留一次 `TouchHaptics.click`，打开录音模式的行为不变。
