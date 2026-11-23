## 本次改动

**机型区分（你要求的核心）**
- 只有 **OPPO Pad Mini（OPD2515）** 才会启用「横屏画布旋转」兜底
- 其他平板、所有手机：完全不碰，行为跟以前一样（系统方向本来正常）
- 渲染方式也分开：Pad Mini 用 TextureView（能被旋转），其余继续用 SurfaceView（性能更好、HDR 更稳）

一共四道门控，任一不满足就不启用：
1. 机型在名单里（只 OPD2515 / OPPO Pad Mini）
2. 系统方向确实没转成功
3. 画布旋转条件 = 上面两条同时成立
4. 渲染方式也按同一条件切换

**修掉一个日志错误**
- 之前 `real=` 打的是判定结果而不是真实方向，出现 `real=L ok=false` 这种自相矛盾，误导排查
- 现在分别打真实的 `real` 和判定 `ok`，另加 `gate=` 显示机型门控是否通过

装上后日志会有：
```
device gate: model=OPD2515 device=... canvasRotation=true
round=1 want=L real=P ok=false gate=true canvasRotate=true
```
手机上则是 `canvasRotation=false`、`gate=false`，一定不会旋转。

**上一版已含**
- 平板旋转画布居中（修偏左/被截）
- 跳过片头片尾「取消」立刻消失
- 下一集预加载气泡正确亮起
- 快捷切号浮层用服务器头像
