## 音乐模块第四批（4 项）

### 状态栏媒体卡片：manifest 的 action 改对了

上一版把类改成了 `MediaLibraryService`，但 manifest 里还写着 `MediaSessionService` 的 action
—— 系统按 `MediaLibraryService` 找提供方，压根找不到 ETKN，所以按钮还是灰的。

这版改成：
```xml
<intent-filter>
    <action android:name="androidx.media3.session.MediaLibraryService" />
    <action android:name="android.media.browse.MediaBrowserService" />
</intent-filter>
```

第二个 action 是给「默认音乐播放器探测」用的，ColorOS / 车机靠它识别候选 App。

### 播放列表有记忆了

**根因**：`MusicMemory` 只存了「当前那一首」，没存队列。
重启后播放器里只有 1 条（甚至 0 条）MediaItem，而队列是从播放器的 MediaItem 列表算出来的：

```kotlin
val queue = (0 until p.mediaItemCount).mapNotNull { items[...] }
```

所以播放列表是空的。

现在：
- 记忆里存整条队列（id / 名字 / 歌手 / 封面 tag）
- 启动恢复时按队列逐个还原 MediaItem，并**同时填回 `items` map**
  （否则 `mapNotNull` 依然会把它们过滤掉）
- `setMediaItems(整个队列, startIndex, positionMs)` —— 队列完整，点任何一首都能切

### 排序真的生效了

**根因**：`sortedTracks` 是个普通 getter：

```kotlin
val sortedTracks: List<Item> get() = applySort(tracks, _sort.value)
```

Compose 读到它不会订阅 `_sort` 的变化 —— 点了排序，值算出来变了，**但界面不重组**，
看起来就是「点了没反应」。

现在改成 StateFlow（`combine(_sort, _paged)`），UI 用 `collectAsStateWithLifecycle` 订阅，
一定能刷新。按钮上也显示总数：`播放全部 · 601 首`。

### 播放器整页重做

按你逐条提的 8 点：

```
① 顶部只留「歌曲 / 歌词」两个标签居中，删掉原来右上角两个按钮
② 专辑封面放大（满宽，480dp 上限）
③ 歌名 + 歌手居中
④ 5 行歌词：中间完整、上下逐级淡出（alpha 1 / 0.45 / 0.16）
⑤ 进度条改三段（已播放 / 已缓存 / 未播放），可点可拖
⑥ 底部：循环模式合并成 1 个按钮切 3 态 + 播放列表
⑦ 左右滑动切「歌曲 / 歌词」，点五行歌词也能进，有平滑过渡
⑧ 参考图里划掉的 6 处一律没做
```
