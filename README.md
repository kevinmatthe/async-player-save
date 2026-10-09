# Minecraft Durable Async Saves

面向 Minecraft 1.21.1 / NeoForge 的服务端保存补丁。把玩家和世界 NBT 的磁盘等待移出主线程，让 FTB 在存档持久化完成后才开始打包，并将世界写入失败传递给备份流程。

当前版本：**1.2.0**。保留原 mod ID `local_async_player_save` 和 JAR 命名前缀，升级时替换旧补丁，不同时安装两个版本。这个仓库只包含补丁、测试和说明，不包含 Minecraft、FAWS、FTB 的第三方 JAR 或真实世界数据。

## 我们解决的问题

整点 FTB 备份虽然在后台压缩 ZIP，前置保存仍可能让主线程等待磁盘。最初的玩家异步保存解决了普通自动保存；1.1.0 将 FTB 保存改为主线程捕获快照、备份线程等待完成。后续采样发现，后台保存仍有 10–24 秒的尾部耗时。

原因之一是 Minecraft NBT 文件使用 `SYNC` 打开，gzip/缓冲输出的每段写入都要等待持久化。一次采样中，约 28KB 的 scoreboard.dat 分四次同步写入，累计 3.10 秒；14 个文件串行写入约 23.68 秒。我们同时观测到 SSD 镜像某个成员的 flush 延迟明显偏高，因此没有把全部延迟归因于软件。详细实验及测量边界见 [实验记录](docs/EXPERIMENTS.md)。

## 1.2.0 的保存流程

1. 主线程获取独立 NBT 快照。
2. 后台以普通 buffered 输出写临时文件，完成 gzip。
3. 每个完整临时文件执行一次 `FileChannel.force(true)`。
4. 原子替换正式文件；玩家和 level.dat 保留 `.dat_old`。
5. 同一批文件的父目录去重，每个目录同步一次。新目录在父目录中的名字也需要持久化。
6. 完成 future 才成功，FTB 才能开始压缩。

每个维度的 SavedData 是一批，level.dat 单独一批。世界任务使用 FAWS 原有单线程 FIFO executor；玩家使用独立的有界 FIFO 队列。区块 region 文件沿用现有实现。架构、线程关系、失败边界见 [架构说明](docs/ARCHITECTURE.md)。

## 兼容性

| 组件 | 已验证目标 |
|---|---|
| Java | 21 |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.251 |
| FTB Backups 2 | 1.0.28，可选；存在时严格限制版本 |
| Fast Async World Save | 2.6，可选；存在时严格限制版本 |

FAWS 未安装时保留原版世界保存路径，世界集中持久化和世界失败跟踪不启用；玩家异步保存继续可用。FTB 未安装时不使用其接入点。其他范围内 NeoForge 版本尚未验证。补丁仅供服务端安装。

## 构建

需要 Python 3.11+、Java 21 JDK，以及目标服务器已经准备好的 Minecraft/NeoForge libraries。构建器编译源码，运行全部回归测试，打包资源；**不会安装到服务器 mods**。

使用已有 Docker 服务器的依赖：

```bash
python3 build.py --container minecraft-crafty --server-dir /srv/minecraft
```

路径是容器内服务器目录。如果容器默认 Java 不是 21，用 `--java /path/to/jdk21/bin/java` 指定。构建器优先识别常见 Debian Java 21 路径，并验证实际版本。

使用本地依赖和 JDK：

```bash
python3 build.py --libraries-dir ./dependencies/libraries --java /opt/jdk21/bin/java
```

也可使用 `MINECRAFT_CONTAINER`、`MINECRAFT_SERVER_DIR`、`MINECRAFT_LIBRARIES_DIR`、`JAVA21` 环境变量。容器构建使用独立临时目录并在结束后清理；优先使用映射后的 SRG JAR，避免加载未映射的同名服务器类。产物在 `build/local-async-player-save-1.21.1-1.2.0.jar`。

## 安装与回退

正常停服，保存当前补丁 JAR，删除 mods 中的旧补丁，放入新 JAR 后启动。FAWS、FTB 原 JAR 保持不变。不要热替换正在加载的模组。

回退同样需要正常停服后换回旧补丁，存档格式保持原版 gzip/NBT，不需要回滚世界。回退会恢复旧版性能和失败处理边界。

## 耗时与错误

可选 JVM 参数：

```text
-Dlocal.asyncplayersave.logTimings=true
```

日志分别报告排队时间 `queueMs` 和后台完成持久化的时间 `ioMs`，包含文件 force、发布和目录 force。后台错误日志带目标路径和异常；不会通过跳过持久化确认来宣布成功。

世界批次失败后，在主线程恢复 SavedData dirty，后续自动保存会重试。失败检查点持续有效，直到相应路径重新提交并成功；备份不能消费掉失败后把旧数据误判为已保存。发布前 IO 失败最多尝试三次；开始发布后的失败停止本次重试，避免覆盖刚保留的上一版。

文件写入/force 在发布前失败时，正式文件保留旧版。发布后目录 force 失败，正式文件可能已经是新版，但持久化没有获得确认：仍报告失败。批量替换不是跨文件事务。完整限制见 [可靠性说明](docs/ARCHITECTURE.md#可靠性边界)。

## 验证

回归测试覆盖顺序、非阻塞提交、固定检查点、失败持续可见、按路径恢复、队列满、原子替换、文件/目录同步、备份旧版和故障清理。大块随机 NBT 测试确认完整 gzip 可以在 force 时读回，且只有一次文件 force 和一次目录 force。

运行时验证必须使用独立世界和配置。`ftbselftest`、`worldselftest`、`restoretest`、`worldrestoretest` 等参数是隔离验证工具，**不要在正式世界启用**。测试会创建 FakePlayer、暂时阻塞 IO 队列、制造目标路径冲突并触发 FTB。使用方法和验收项见 [验证说明](docs/VALIDATION.md)。

## 文档

- [架构与失败边界](docs/ARCHITECTURE.md)
- [实验记录与判断依据](docs/EXPERIMENTS.md)
- [验证说明](docs/VALIDATION.md)
- [版本变更](CHANGELOG.md)

补丁代码使用 [MIT License](LICENSE)。第三方模组和 Minecraft 的许可分别适用。
