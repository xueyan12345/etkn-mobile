## 本次改动（修 1.7.0 的两个关键问题）

### 一、缓冲条不动的原因找到了

日志显示 1.7.0 缓存确实在写盘了（`disk usage=620MB`），但条一直不动。

根因：我用 `getCachedSpans()` 遍历已完成的 span 来算缓冲范围，
而 **SimpleCache 里「正在下载的区域」是 hole，不在 getCachedSpans 的结果里**——
播放位置后面那段恰恰多数还在 hole 中 → 算出来恒为 0 → 条永远停在播放点。

现在改用官方 `getCachedLength(key, position, length)`：
- 返回正数 = 从该位置起连续可用的字节数
- 返回负数 = 这里是洞
它内部把 span 和 hole 一起算，正是要的语义。

### 二、Exo 播放时根本没写缓存

上一版不管什么内核都用代理地址，而 Exo 的数据源遇到 `127.0.0.1` 是「透传不缓存」
→ Exo 播放全程没往磁盘写任何东西。

现在按内核分流：
- **Exo → 直连上游 URL**，CacheDataSource 自动边播边写（正常路径）
- **mpv → 走本机代理**，代理内部用同一个 CacheDataSource

两边共用同一份 SimpleCache 和同一个 key（itemId），互通。

### 三、设置页的占用/清空对不上

你截图看到「0 MB」，而日志里是 620MB。

根因：缓存已经迁到官方目录 `cacheDir/videocache`，
而设置页还在读旧目录 `mediafiles/`（早就空了）。

现在：
- 占用显示改成读 `VideoCache.usedBytes()`
- 「清空磁盘缓存」同时清官方缓存和旧目录残留
