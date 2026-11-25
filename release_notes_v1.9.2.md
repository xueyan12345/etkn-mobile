## 修：单集详情页看不到季卡

### 原因

剧集层现在会自动跳到具体某一集，但 `DetailViewModel` 里取 `seriesId` 的判据只认两种：

```kotlin
val seriesId = when {
    base.isSeries -> base.id
    base.isSeason -> base.seriesId
    else -> null        // ← Episode 落进这里，是 null
}
```

`seriesId` 为 null 时，`seasons` 和 `target` 两个请求都不会发 →
`data.seasons` 是空列表 → 季卡区 `if (data.seasons.size > 1)` 不成立 → 整块不渲染。

### 修复

`Episode` 也算上（它的父级就是 series）：

```kotlin
base.isEpisode -> base.seriesId
```

季卡位置不变，仍在「演职人员」上方（剧集列表 → 章节 → 季卡 → 演职人员）。
