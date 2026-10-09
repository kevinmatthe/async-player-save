# Minecraft 1.21.1 玩家与 FTB 保存异步补丁

版本 1.1.0。2026-10-09 12:35 已正常停服替换并在正式服启动；旧 JAR 与配置保存在 `build/rollback-20261009-1233/`。目标环境：Java 21、NeoForge 21.1.251、FTB Backups 2 1.0.28、Fast Async World Save 2.6。

## 行为

- 普通周期自动保存及 FTB 触发的保存：在服务器线程获取并复制玩家 NBT，后台单线程按顺序写文件。保留 NBT 的 SYNC 选项和 `.dat_old`。
- FTB 保存提交返回的 future 同时包含捕获与后台写入完成。服务器线程不等待磁盘；FTB 自己的备份线程等待玩家写入、主线程保存事件通知和世界写入队列完成，再压缩。
- 在服务器线程离开捕获阶段前取得玩家写入检查点，避免后续读取/手动保存消费失败结果。检查点无需额外队列容量，清理时不移除后续保存。
- 玩家写入失败、捕获异常或备份捕获时队列满载会令 FTB 保存 future 失败；强制 FTB 检查已经完成的异常 future，避免跳过失败。备份捕获满载时中止备份，不在主线程等待磁盘。
- 读取同一玩家、手动保存及关服仍有同步屏障；普通周期保存满载时保留原有等待并回退同步写入的行为。
- FTB 的 JAR 不变，通过优化 JAR 的 Mixin 接入。FTB 可选依赖限制为 1.0.28；已安装但版本不符时拒绝启动，而不是静默使用错误调用点。

## 构建和验证

执行 `python3 build.py`。使用当前 Minecraft 容器内的精确运行时依赖编译，产物为 `build/local-async-player-save-1.21.1-1.1.0.jar`，不会写入正式服 mods。

Java 21 回归测试：`SaveQueueTest`、`AtomicSaveTest`、`BackupSaveTest`，覆盖顺序、非阻塞提交、读取屏障、原子替换、失败保留旧文件、备份 future、上下文恢复、满队列检查点、失败消费竞态和失败后的恢复。

独立完整模组测试服：容器 `/tmp/ftb-backup-validation-20261009`，独立世界和配置，监听容器回环地址端口 25585，BlueMap Web 服务禁用。只在隔离环境传入 `-Dlocal.asyncplayersave.selftest=true -Dlocal.asyncplayersave.ftbselftest=true`，通过真实 FTB 保存调用、FakePlayer 和人为阻塞 3 秒的世界 IO 验证 tick 继续、ZIP 新玩家数据、备份保存状态恢复和注入失败禁止压缩。`-Dlocal.asyncplayersave.restoretest=true` 校验从 ZIP 提取的新世界可启动并读取预期玩家数据。

## 限制

- **Fast Async World Save 2.6 自身会记录并吞掉部分世界写入错误。**本补丁的世界检查点保证此前任务已经执行结束，不能将其吞掉的错误转为 FTB 失败；不声称新增了完整的世界写入成功检测。
- 沿用 FTB 1.0.28 的 30 秒等待超时及备份期间的保存开关。超时会中止备份并恢复保存；已经提交的后台 IO 不会因此被取消。
- 不改变 FTB 对玩家退出或其他模组额外写入的现有一致性边界；主线程仍需要捕获 NBT、保存区块和执行保存事件，不保证保存完全无慢 tick。
- 机器断电或强制结束进程仍会丢失未落盘的数据。正式服已验证一次无人在线的手动 FTB 备份；不能据此保证多人在线或后续整点保存无慢 tick。

正式服应用需要正常停服后替换旧优化 JAR，再启动；不要同时保留 1.0.0 和 1.1.0。FTB 与 Fast Async World Save 原 JAR 保持不变。回退同样需正常停服后换回旧优化 JAR，不需要回滚世界。

## 正式服验证与磁盘诊断（2026-10-09）

12:38:24 触发真实 FTB 备份，12:39:13 完成，ZIP 1.2GB，读取归档 level.dat 校验通过，所有世界保存开关恢复。Spark 5ms Java 采样：服务器 saveEverything 路径约 35ms；FTB 后台等待保存约 8.8s。采样共 697 ticks，TPS 20，最大 tick 441.81ms，仍有较短慢 tick；没有此前约 8 秒的主线程保存等待。该次无人在线，玩家写入及失败路径依赖隔离测试。完整证据记录见 `build/manifest-1.1.0.json` 和正式服 `config/spark/profile-2026-10-09_12.39.00.sparkprofile`。

将同一份 3754 字节 level.dat 内容在 SSD/HDD 数据集临时目录中各测试三次，回读校验后删除测试文件。三次中位耗时：普通写入 0.091/0.122ms、结尾 fsync 371.64/9.33ms、每次写入 O_SYNC 706.21/38.90ms（SSD/HDD）。普通写入返回不代表持久化。原始记录 `build/diagnostics-20261009/small-file-benchmark.json`。并行 40 秒 iostat：nvme1n1 平均 write/flush 93.03/191.03ms，nvme2n1 为 2.36/4.47ms。慢盘序列号 ZTA2512KA2251604ED，PCI 0000:07:00.0；另一盘 ZTA2512KA2251604E4。nvme1 同时存在较高 discard 延迟，尚未通过控制变量区分 TRIM、内部回收、固件或器件因素；没有改变 sync/autotrim，也没有写裸设备。
