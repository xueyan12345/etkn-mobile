## 删掉我自己写的下载循环（OOM 元凶）

### 关键认知：Exo 自己就会缓存，我不该再写一条

1.8.4 的日志已经证明，只要在 `DefaultLoadControl` 里设好字节阈值，
**Exo 自己的 CacheDataSource 就在稳定往下缓存**：

```
22:39:50  aheadB=0MB    disk=0MB
22:39:52  aheadB=61MB   disk=64MB
22:39:55  aheadB=119MB  disk=128MB
22:39:57  aheadB=179MB  disk=192MB
```

而我额外写的 `CacheWriter` 主动下载循环，不仅重复劳动，还带来两个致命问题：

1. **和播放器抢同一段缓存** —— SimpleCache 不允许两个写入者写同一区间
2. **每 64MB 重建一次数据源** —— 对象堆积，256MB Java 堆撑满 → OOM 闪退

### 本次改动：删代码，不是加代码

```
删除：runCacheLoop、CacheWriter 循环、chunk 分片、posBytesOf 那一整套
保留：setTargetBufferBytes = 用户阈值（这才是真正的修复）
恢复：播放器数据源带写入（没有第二个写入者了，不会冲突）
缓冲条：getCachedBytes（读真实落盘量）
```

### 现在的缓存架构（官方标准路径）

```
播放器 → CacheDataSource ─┬─ 命中 → 直接读 SimpleCache
                          └─ 未命中 → 走上游，同时写入缓存
                              ↑ 只此一条写入路径，不会再冲突
上限控制：
  · 字节 → setTargetBufferBytes(用户设的 MB)
  · 时间 → setBufferDurationsMs(max = 用户秒数 + 1分钟)
  · 谁先到谁停
淘汰：LRU 自动挤掉最旧的（播过的保留，可回拖）
```

### 另外一个改动

缓存目录从 `cacheDir` 换到 `filesDir`。
Android 允许系统随时清空 `cacheDir`（OPPO 尤其积极），
放那里会出现「刚写完就被回收、disk 一直是 0」。
