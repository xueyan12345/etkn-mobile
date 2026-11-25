## 本次改动：找到了系统级横屏的正确做法

你这个思路是对的 —— 不该靠「自己把画布转 90°」这种变通，应该让系统真的横过来。查了 Android 官方文档，确实有正解，而且**之前一直没生效是因为属性名写错了**。

### 官方原文（Android 16 行为变更）

> For apps targeting Android 16 (API level 36), orientation, resizability, and aspect ratio restrictions no longer apply on displays with smallest width >= 600dp.
>
> 被忽略的 API 包括：`screenOrientation`、`resizableActivity`、`setRequestedOrientation()`、`getRequestedOrientation()`

这解释了为什么点旋转没反应 —— 不是我们的代码有问题，是系统按设计忽略了方向请求。

### 官方给出的退出方式

> Every app that isn't fully ready can temporarily override this behavior by opting out, which results in the previous behavior of being placed in compatibility mode.

```xml
<property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY"
          android:value="true" />
```

### 之前为什么一直失败

清单里写的是 `PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE` —— **这个属性名在官方文档里根本不存在**。
属性名错了，系统当然不理我们的方向请求，日志里才会一直是「请求横屏三次、实际一直竖屏」。

### 改正之后一次解决三件事

| | 之前（画布旋转兜底） | 现在（系统真横屏） |
|---|---|---|
| 横屏 | 自己转画布，系统还是竖屏 | 系统真的横过来 |
| 渲染方式 | 必须用纹理视图（才能被旋转） | 可以用普通画面直通 |
| 杜比视界偏色 | 纹理层丢元数据 → 偏色 | 直通不丢元数据 → 颜色正确 |
| 4K 流畅度 | 需要软件解码兜底 → 掉帧 | 硬件解码直通 → 不卡 |

之前为了「横屏」被迫用纹理视图，纹理视图会丢掉杜比视界的动态元数据，所以偏色；
为了绕开偏色又想软件解码，4K 就卡。三个问题是串在一起的，
根因就是「系统不给横屏」。

### 这一版的具体改动

- 清单属性改成官方正确的名字
- 停用「画布自己转 90°」的兜底（不再需要）
- 恢复杜比视界片源的硬件解码
- 杜比视界片源不再切换内核（不需要了）
