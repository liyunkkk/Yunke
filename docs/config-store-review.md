# 仓储审查需处理（源码静态检查，未编译）
1. seed把profile.modelId selection当apiModel导致限额丢失，现有测试自己设置selection/api-model不同已暴露。
2. SharedPreferences.commit=false仍可能已改内存，check(false)不回滚；bind重试以目标contains就当durable成功，confirm又删唯一draft危险。需失败恢复/dirty阻断及确保持久化，覆盖模拟“内存改了但commit=false”测试。
3. 新格式decode用宽松SubAgentProfile.fromJson会把非法enabled、reasoning_memory类型、坏reasoning/imageResolution/tier悄悄清理。新archive需严格验证，迁移容错分开，overwrite前必须拒绝坏数据。
4. revision只有全局，建议owner版本或owner CAS token，B修改不应使A编辑失效；全局通知可保留+distinctUntilChanged，禁止用全局revision当owner编辑锁。
5. Robolectric测试prefs()的RuntimeEnvironment.getApplication().getSharedPreferences缺泛型上下文，显式getApplication<android.app.Application>()。
父已在同工作树AppState/Root接好你约定editor/local接口、UI与draft绑定；不要编辑这两个大文件。待你完成后父还会整合runtime与backup。confirmBoundDraft只有Room save成功后调用，但你仍需绑定关联和durable证明。请查阅本文件核对。

备份模块worker会调用public fun validateArchive(raw:String) 做无副作用的新格式校验，请给仓储提供该方法（调用严格decode即可）；不修改backup文件。父会负责UI仓储refreshAfterRestore。
