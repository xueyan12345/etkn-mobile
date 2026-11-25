## 找到根因了：Exo 有个隐藏的字节天花板

### 日志证据

```
19:57:31  posB=2489MB  aheadB=91MB    ← 峰值
19:57:43  posB=2514MB  aheadB=66MB    ← 一路下降，不再往后补
```

你设的是 400MB，实际只缓存到 **136MB 就不再增长**。

### 根因（官方源码确认）

Exo 的 `DefaultLoadControl.shouldContinueLoading` 停止条件是**或**关系：

```java
bufferedDurationUs >= maxBufferUs   ||   targetBufferSizeReached
```

- 前者看时间（我设成 1260 秒，够大）
- **后者看字节，而默认值是 `C.LENGTH_UNSET`，由 Exo 按轨道估算——通常只有几十 MB**

任一满足就停止加载 → **你的 400MB 设置永远轮不到生效**。

这就是为什么前面几版怎么调都没用：我一直在调时间参数，而真正的天花板在字节这一侧，且是隐式默认值。

### 修法

```kotlin
.setTargetBufferBytes(cacheMb * 1024 * 1024)   // 直接等于你设的 400MB
```

时间上限仍是 `cacheMemorySeconds + 1 分钟余量`，两个阈值谁先到谁停，
**且都严格等于你在设置里填的值**。

### 顺带修的：缓存实例重复打开

日志显示 SimpleCache 被打开了两次（一次 keys=2，一次 keys=0），
后一次读到空索引，导致按 itemId 查缓存查不到。
现在改成单实例：已经有实例就直接复用，绝不重复打开同一目录。

### 日志导出（你反馈的两个问题）

- **一开就 25K**：原来是先灌了 2000 行历史日志当种子，已删掉
- **清空后再无新日志**：`logcat -T` 的时间格式和输出格式对不上导致进程秒退，
  改成先 `logcat -c` 清缓冲、再不带 `-T` 跟随
