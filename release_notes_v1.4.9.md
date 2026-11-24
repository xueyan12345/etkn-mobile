## 本次改动

### 一、修复「切到兼容内核后画面躺着的」

日志实证两个错误叠在一起：
```
ETKN-Mpv: video-rotate=90                    ← 让 mpv 自己转了 90°
round=1 want=L real=P ok=false canvasRotate=true  ← 外层画布也在转
```
两层旋转叠加（90° + 90° = 180°），所以出现「界面是横的、画面躺着的、字幕竖排」。

现在：外层画布照常旋转（界面和画面框都对），**mpv 的 video-rotate 固定 0**，只转一次。

### 二、杜比视界 profile 5 偏色（谷雨）

你逐个内核验证的结果很有价值：
- Exo：横屏正常，但偏色
- mpv：方向错乱，也偏色

说明偏色不是「选哪个内核」的问题，是**色彩链路**的问题。

日志给的答案：
```
mpv_set_property(video-params-primaries) returned error property not found
mpv_set_property(video-params-gamma)     returned error property not found
```
上一版我给 mpv 设的这两个色彩属性**根本不存在**，完全没生效。

真正的原因在这里：`BlurayMediaSourceFactory.baseLayer()` 只处理了 profile 7，
**profile 5 被原样交给杜比解码器**，而平板走纹理视图时杜比专用的色彩元数据（IPTPQc2）
在这一层丢失 → 被当普通 BT709 解释 → 偏绿。

现在：**profile 5 也降级成普通 HEVC**，交给系统标准解码器。
系统按码流里的标准色彩信息处理，不再有 IPTPQc2 被误读的问题。
代价是失去杜比动态元数据（画质略降），但颜色正确、能正常看。

### 三、HDR 片源发黑（走马上任）

日志实证 `hdr detected=true → 需要 tone-map` 之后确实切了 mpv，
但那次的 video-rotate=90 造成了画面躺着的。现在旋转问题解决了，
HDR 走 mpv 做亮度映射这条路径可以正常用了。

### 四、设置里的内核选择仍然优先

所有自动切换只在「自动」模式下发生。手动选了 ExoPlayer 或 mpv 就不会被覆盖。
