## OOM 卡死原因（我的死循环）

日志实证：
```
cache-writer cached=0MB chunk=1MB target=1043MB   ← 反复出现
calc aheadB=398MB disk=1810MB                      ← 数据其实有！
Throwing OutOfMemoryError ... 268435456 (256MB)    ← 堆爆了
```

### 根因

循环里用 `getCachedBytes(itemId, 0, total)` 从**文件开头**量已缓存量。
但你从 400 秒位置续播，前面 643MB 处本来没有缓存数据 ——
中间有洞 → 这个 API 永远返回 0。

于是循环认为「一点都没缓存」→ **每秒重新 new 一个 CacheDataSource + CacheWriter**
→ 对象越堆越多 → Java 堆 256MB 撑满 → OOM → 卡死。

### 修法

**① 从窗口起点量**（不是从 0）
```kotlin
val cachedAhead = store.getCachedBytes(itemId, 播放位置字节, target - 播放位置字节)
val cached = 播放位置字节 + cachedAhead
```

**② 加无进展保护**
如果连续 3 次算出的起点没变，说明写不进去，休眠 3 秒等待，
绝不再空转建对象。

### 顺便确认的进展

这份日志里 `aheadB=398MB`、`disk=1810MB`、key 正确（`has=true`），
说明**缓存本身已经在正常工作了**，前面几版的核心问题都已解决。
