# BlockRacingChunky

[BlockRacing](https://github.com/LQSnow/BlockRacing) 的附属插件，用于提前计算随机传送的坐标，通过使用 Chunky 插件预生成区块，做到玩家秒级传送，减少服务器卡顿。

## 依赖
- BlockRacing 版本 3.5 及以上
- [Chunky 插件](https://hangar.papermc.io/pop4959/Chunky)

## 使用

将本插件、BlockRacing、Chunky 一起放在服务器 `plugins/` 下即可


## 命令
- `/brc status` 查看状态
- `/brc pause` 手动暂停生成
- `/brc resume` 手动恢复生成
- `/brc reload` 重新加载配置

## 默认配置

```
# 目标世界名
world: world

# 随机坐标范围（±range）
range: 10000

# 传送池目标数量（ready + pending + inflight）
pool-target: 30

# 每个候选点预生成半径（单位：chunk）
pregen-radius-chunks: 10

# Chunky 形状与遍历模式
shape: circle
pattern: concentric

# 轮询间隔（tick）
tick-interval: 40

# TPS 低于此值暂停生成
tps-pause: 18.0

# TPS 恢复到此值继续生成
tps-resume: 20.0
```

