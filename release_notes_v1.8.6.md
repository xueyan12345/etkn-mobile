## 修闪退（缓存这次是好的）

### 先确认：缓存已经达标

1.8.5 日志实证：
```
04:03:18  aheadB=400MB  disk=662MB   ← 正好是设定的 400MB 上限
04:03:43  aheadB=360MB                ← 播了 25 秒，消耗 40MB
04:04:10  aheadB=0MB    disk=662→746MB ← 补满新的一段
```

**缓冲条按设定工作：播到哪就往前补到 400MB。** 这条线是通的。

### 闪退根因：同一部片两次选核结果不同

```
04:03:18.260  自动选核 → mpv（hdr=true dv=false codec=hvc1.2.4.l153.90）
04:04:23.720  自动选核 → ExoPlayer（dv5=true codec=dvhe.05.08）
```

这是杜比视界 profile 5 的片源（带 HEVC 兼容层）。第一次回调 Exo 只给出
「普通 HEVC + HDR」，判成 `hdr=true` → 选 mpv；第二次才拿到真正的 `dvhe.05`。

**后果**：mpv 硬解 4K HDR 时缓冲区吃满：
```
AidlBufferPool: 11 × 97MB = 92MB，used 一直 10~11（从不释放）
Dalvik used=216MB / 256MB → OOM
```

### 两处修复

**① 选核不要抢在轨道信息完整之前定案**

识别出「HDR 但没看到 DV 标识」且 codec 像 HEVC/DV 兼容层时，
先等下一次回调（那时 DV 元数据已到），最多等 2 次，之后照常定案。

**② mpv 4K 解码少吃内存**

- `vd-lavc-dr`：yes → **no**（关掉直渲，解码器不再囤 11 个 4K buffer）
- 新增 `vd-lavc-threads` 限制解码线程数（低内存机 2，其余 4）
