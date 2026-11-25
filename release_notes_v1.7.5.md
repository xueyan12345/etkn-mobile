## 这次修了两处，一处是真根因

### 真根因：Exo 隐藏的字节天花板（1.7.4 已修，日志确认生效）

日志证据（1.7.3）：
```
20:11:38  posB=53MB   aheadB=129MB   ← 峰值
20:12:13  posB=140MB  aheadB=136MB   ← 开始下降，不再往后补
```
你设 400MB，实际只到 141MB 就不再增长。

官方源码 `DefaultLoadControl.shouldContinueLoading` 的停止条件是**或**关系：
```java
bufferedDurationUs >= maxBufferUs   ||   targetBufferSizeReached
```
后者默认由 Exo 按轨道估算——**通常只有几十 MB**，所以你的 400MB 永远轮不到生效。

1.7.4 已改成 `.setTargetBufferBytes(400MB)`，日志确认：
```
loadControl min=8s max=1260s targetBytes=400MB
```

### 1.7.4 我搞坏的一处（本次修回）

读缓冲范围我换成了 `isCached` 逐段探测，结果 `aheadB` 全变成 0。
原因：`isCached` 只认「已完全 commit 的连续段」，而播放器正在读的那段
CacheDataSink 还在写（5MB 一个 fragment），所以永远 false。

现在改成**两条路取较大值**：
- `getCachedLength` —— 能反映已完成的连续区域（1.7.3 靠它拿到 129MB）
- `getCachedSpans` 累加 —— 补充已落盘的段落
- 两者任一有值就用大的那个，宁可少画也不虚报

### 缓存实例重复打开（1.7.4 已修）

日志显示 SimpleCache 被打开两次（keys=2 然后 keys=0），
后一次读到空索引导致按 itemId 查不到。现在改成单实例复用。
