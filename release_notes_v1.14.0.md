## 音乐模块第三批（3 项）

### 状态栏媒体卡片按钮现在能点了

**这是 Android 平台规范，不是 OPPO 特有适配。** 官方文档（Media3 / Background playback）原文：

> By default, playback resumption is turned off. This means the user can't resume
> playback when your service isn't running.
> **Note: The Android System UI resumption notification is only available
> if you implement a `MediaLibraryService` to serve a content library.**

对照你的实验：
```
系统音乐设为默认（后台已关）→ 卡片播放键可点，点了续播
ETKN 设为默认              → 同一个位置灰色点不动
```

差别就在这里 —— 之前 ETKN 继承的是 `MediaSessionService`，而系统 UI 的续播卡片
**只对 `MediaLibraryService` 开放**。

三处改动：

```
① MusicService 改继承 MediaLibraryService
   onGetSession 返回 MediaLibrarySession（内容库返回空树即可，
   系统只要求类型对，不要求真的暴露曲库）

② onPlaybackResumption 补 isForPlayback 参数（官方新签名）
   isForPlayback=true 正是蓝牙耳机/车机"真的要播"的场景

③ Service 起来时把上次那首预装进播放器并 prepare
   → mediaItemCount=1、duration 已知、COMMAND_PLAY_PAUSE 可用
   → 卡片显示封面+时长+可点播放键（对标系统音乐那张 00:00 / 04:00）
```

**另外修了一个会毁掉续播的地方**：`onTaskRemoved` 原来调 `stop()`，
而 `stop()` 里有 `clearMediaItems()` —— App 一被划掉播放器就清空，卡片立刻变灰。
现在只暂停、保留 MediaItem。

### 重启后点迷你条能进播放页了

**根因**：`NowPlayingScreen` 开头 `if (track == null) { nav.back(); return }`，
而重启后 `state.current` 是 null → 页面刚推上去就立刻 pop 回来
→ 用户看到的就是「抖了一下，不弹出来」。

现在 `state.current` 为空时用记忆里的那首渲染（和迷你条同一套判断），
只有连记忆都没有（从没播过）才返回。

### 搜索结果里的专辑/歌手有封面了

**根因**：上一版用 `id = "album:$名字"` 造了个假 ID，
而卡片取图是按 `item.id` 拼 `/api/tv/Items/{id}/Images/Primary` ——
服务端不认识 `album:xxx`，直接 404，于是全灰。

现在 id 保持该专辑下第一首歌的**真实 itemId**（封面能正常拿到），
只把显示名换成专辑名/歌手名。点进去时按名字再搜一次列出对应的歌。
