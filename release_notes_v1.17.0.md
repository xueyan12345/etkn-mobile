## 系统控制中心/车机播放键现在能点了

**这一条查了很久，最终用 adb 实测定位到三层原因，全部修掉。**

### 第一层：Service 从没被创建过（已修）

```
dumpsys media_session   → Sessions Stack - have 0 sessions
dumpsys activity services → (nothing)
ps -A | grep etkn        → 进程在，但 MusicService 不存在
dumpsys package          → manifest 登记的 action 完全正确
```

`MusicService` 原来只在 `play()` 里 `startService`，所以「从没播过歌」时它压根不存在。
现在 App 启动就拉起，Session 立刻存在。

### 第二层：ColorOS 拦截 MEDIA_BUTTON 拉起 App（需要你手动开）

```
OplusAppStartupManager: *Do not want to launch app xyz.etkn.mobile/10687
for broadcast Intent { act=android.intent.action.MEDIA_BUTTON
cmp=xyz.etkn.mobile/...MediaButtonReceiver }
```

系统确实把播放键发给了我们，但 ColorOS 拒绝拉起进程。
这个白名单只能手动开（adb 改不了，需要 `oplus.permission.OPLUS_COMPONENT_SAFE`）：

```
设置 → 电池 → 应用电池管理 → ETKN
  → 允许自启动 ✅
  → 允许后台活动 ✅
```

**设置页已加「允许后台运行（ColorOS 需手动开）」引导入口**，点「去设置」直接跳过去。

### 第三层：`onTaskRemoved` 里 `stopSelf()` 销毁了 Session（已修）

**你这句话直接点破了根因**：
> 「主要人家系统自带的那个，都不用点，它默认的播放是亮的，现在是暗的」

系统音乐播放键一直是亮的，因为它的 MediaSession **一直在**，控制中心有对象可绑。
我们 `onTaskRemoved` 里调了 `stopSelf()` —— Service 一停 Session 立即销毁，
ColorOS 日志就是：

```
OplusQsMediaPanelViewController --> bindMediaCarouselController
    historyList is empty
```

没有 history、没有 session → 按钮是暗的，**点击也不会发命令**（所以点几下完全没反应）。

现在 `onTaskRemoved` 只暂停、保留 Session 和 MediaItem，不 stopSelf。
代价是一条常驻通知 —— 和系统音乐行为一致。

### 实测结果

```
am kill（模拟上滑划掉 App）→ 进程在、Session 在（1 sessions）
点播放 → state=PLAYING, position=16811, metadata=雨一直下/张宇
```

**注意**：`force-stop`（开发者选项里的强制停止）任何 App 都扛不住，系统音乐也一样，
这是 Android 的机制，不是 bug。

## 其它修复

- **按字母排序**：改用系统 `Collator(Locale.CHINA)`，中文按拼音、非中文排后面
  （原来 `#` 排在 `A` 前面，而且汉字按 Unicode 区段猜首字母不准）
- **播放全部/随机按钮**：缩到 32dp，不再比顶部标签还大
- **播放页封面**：往上提，紧贴「歌曲/歌词」标签下方
- **底部播放控件**：再往上移一点
