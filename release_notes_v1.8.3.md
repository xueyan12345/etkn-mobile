## 修闪退 + disk=0 的根因

### 闪退原因：两个写入者抢同一段缓存

`SimpleCache` 不允许两个写入者写同一个 key 的同一区间。
之前：
- 播放器的数据源（默认带写入）在写
- 我新加的 CacheWriter 也在写同一段

冲突 → 崩溃。

**现在：播放器只读，CacheWriter 唯一负责写。**

```kotlin
// 播放器数据源
.setCacheWriteDataSinkFactory(null)   // 只读

// CacheWriter 专用数据源
.setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(store))  // 唯一写入者
```

### disk=0 最可能的原因：缓存写在 cacheDir 被系统清了

Android 允许系统随时清空 `cacheDir`，OPPO ColorOS 尤其积极。
这正好解释：
```
cache-writer cached=175MB   ← 网络确实读了 175MB
disk=0MB                    ← 但磁盘上是空的
```

**现在缓存目录改到 `filesDir`**，不会被系统自动清理。

### 去掉引发崩溃的并发访问

播放期间不再调用 `store.keys` / `store.cacheSpace`（会与播放线程冲突），
只打我们自己维护的「已缓存 / 目标」两个数字。
