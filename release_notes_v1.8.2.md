## 缓存功能已跑通大半，本次修两个收尾问题

### 好消息：缓存真的在下载了

1.8.1 日志：
```
21:33:25 cache-writer cached=0MB   target=661MB
21:33:30 cache-writer cached=175MB   ← 真的在下，而且单调递增
```
（前面几版从来没有增长过）

### 本次修 ①：网络抖动导致整轮失败

```
21:33:34 cache-writer failed: stream was reset: INTERNAL_ERROR
```
一次性让 CacheWriter 写 661MB，中途任何一次断连整轮作废。

现在改成**每轮只下 64MB**：
- 成功 → 继续下一轮
- 失败 → 已下的部分保留，下一轮从新位置继续
- 永远不用从头再来

### 本次修 ②：缓冲条读不到已缓存的数据

日志同时出现矛盾：
```
cache-writer cached=175MB     ← 写入端说存了 175MB
calc aheadB=0MB disk=0MB      ← 查询端说啥也没有
```

怀疑写入用的 cache key 和查询用的不是同一个
（CacheDataSource 内部重建 DataSpec 时可能丢了 key，退回用带 token 的完整 URL）。

本次加诊断日志：每 15 秒打印缓存里所有 key、占用、以及「要找的 key 在不在」。
这次日志会直接显示真相，不用再猜。
