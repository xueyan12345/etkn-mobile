## 音乐模块第二批（5 项）

### 系统控件 / 蓝牙耳机现在能控制了

**根因**：`MusicService` 里只设了 `setSessionActivity`，**完全没有 `MediaSession.Callback`**。
系统状态栏那个方形媒体控件、蓝牙耳机线控发的都是 MediaController 命令，走 Session 的 Callback；
没有 Callback 就没人接，表现就是「点了一点反应都没有」。

现在补了三处：

```
① onConnect          → 声明播放/暂停/上一首/下一首等命令，系统控件才会显示按钮
② onPlaybackResumption → App 不在后台点耳机时，返回"上次那首 + 上次进度"，系统才能续播
③ onAddMediaItems    → 把系统传回的 MediaItem 还原成可播条目
```

这样「状态栏控件点播放」「蓝牙耳机点播放/下一首」都能真正拉起 ETKN 并接着上次播。

### 音乐首页顶部不再压状态栏

删掉「音乐」大标题时，它内部带的 `windowInsetsPadding(statusBars)` 也一起没了，
整个页面从屏幕最顶端开始画，压住了状态栏。

现在补上 `windowInsetsPadding(WindowInsets.statusBars)`，高度与探索页一致。

### 搜索现在是真的搜索了

**根因**：把搜索词传给了 `Music?Key=` 参数 —— 但 `Key` 是「分组过滤键」（专辑名/歌手名），
不是搜索词。服务端拿它匹配不到时当作"没有过滤"，直接返回整张列表。

实测证据（音乐库 20，601 首）：
```
Key=邓紫棋        → 601 条（全部，搜索词被忽略）
SearchTerm=邓紫棋 → 14 条（正确）
```

现在改用 `Items?SearchTerm=`，并限定 `ParentId` 只在这个音乐库内搜。

专辑 / 歌手从命中歌曲的 `album` / `artists` 字段派生（实测 ETKN 音乐库里
`Items` 只有 `Type=Audio`，没有独立的专辑/歌手条目）。

### 重启后封面和歌词都在了

**根因**：重启后 `state.current` 是 null，而封面和歌词都直接读它：

```kotlin
Artwork(track?.let { images().music(it, 96) }, ...)   // track=null → 占位图
val t = track ?: return@LaunchedEffect                 // track=null → 永不取歌词
```

现在两者都优先用 `state.current`，没有就退回记忆里存的
`itemId` / `primaryTag` / `sourceId`，重启后立刻有封面和歌词。

### 音乐用独立缓存

**为什么不复用视频那套**：视频缓存是 `LeastRecentlyUsedCacheEvictor(maxBytes)`，
配额一满就淘汰「最久没访问」的 —— 恰好就是「上次播的那首」，
于是重启后封面/歌词/秒播全没了。

音乐的缓存完全独立：

```
目录   filesDir/musiccache（与视频 filesDir/videocache 分开）
配额   自己的设置项，不参与视频的「最大磁盘缓存大小」
淘汰   LRU，但配额由音乐自己决定
```

**新增设置项**：「我的 → 播放 → 音乐缓存」
- 音乐缓存大小：512MB / 1GB / 2GB / 4GB / 8GB / 16GB（默认 2GB）
- 清空音乐缓存（带当前占用显示）

这样播过的歌留在本地，重启后点播放直接从本地起，不用先请求 item 详情 + PlaybackInfo。
