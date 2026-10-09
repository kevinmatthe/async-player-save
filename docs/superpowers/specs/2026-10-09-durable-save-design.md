# 集中持久化和世界写入失败传播

用户已确认采用“普通临时文件写入、完整写完后持久化、原子替换、目录持久化”的优化，要求建立独立 Git 仓库并整理此前研究。

## 边界

Java 21 / Minecraft 1.21.1 / NeoForge 21.1.251 / FTB Backups 2 1.0.28 / FAWS 2.6。保留模组 ID 和原有玩家保存、FTB、手动保存、读取和关服屏障。补丁不修改第三方 JAR、ZFS sync 属性或区块 region 写入。版本 1.2.0 先在隔离世界验证，不自动替换正式服。

## 持久化

AtomicSave.writeBatch(Map<Path, Writer>, Set<Path> keepOld) 先写完并 force(true) 所有临时文件，之后原子发布。同批文件的父目录去重，每个目录在所有替换后 force 一次；future 只有目录同步结束后才成功。要求原子替换，拒绝不支持原子替换时的非原子回退。玩家和 level.dat 保留 _old，通过硬链接已有完整文件保持前一版；不支持硬链接时可复制并 force 备份。世界 SavedData 不新增 _old。创建新目录时也需持久化它在父目录中的名字。

文件写入或 force 失败发生在发布之前，应保留原目标。发布以后目录 force 失败，文件可能已更新但持久性未获确认，必须报告失败；不能保证此时目标仍为旧版。批量替换不提供跨文件事务。临时文件需要清理，清理错误不能覆盖原始失败。

NbtWrites.write(CompoundTag, Path) 采用普通 buffered 输出及 NbtIo 的 OutputStream 重载；AtomicSave 负责唯一的文件 force。gzip 必须完全结束，不能以 Java flush 代替持久化。

## 世界保存

以取消式 HEAD 注入替代 FAWS 对 DimensionDataStorage.save 和 LevelStorageAccess.saveLevelData 的写入任务，仍使用 FAWS 的原单线程 FIFO executor。主线程捕获独立 NBT，每个维度的 SavedData 一批提交；后台写文件和同步目录。仅 FAWS 2.6 存在时启用这些注入。

WorldSaveQueue 跟踪每个目标文件最新的完成 future。检查点在备份捕获阶段取得固定快照，后续保存不能掩盖此前失败。失败 future 持续保留到该路径重新成功提交的 future 替代；不能因一次备份消费而清除失败。后台失败通过主线程恢复本批 SavedData dirty，以便下一轮重试。截图/序列化或队列拒绝也要保留 dirty 并使备份失败。世界 IO 每批最多尝试三次，最终失败才恢复 dirty。

FTB 保存检查点涵盖玩家、已捕获世界任务、主线程保存事件与 FAWS 队列屏障。世界文件、file force、替换、目录 force 任何失败都禁止 ZIP 成功。已有 FTB 超时和保存开关恢复流程保留。FAWS 之外的写入和硬件错误不声称被全部覆盖。

## 验证与仓库

先写失败测试，覆盖完整内容、旧版保留、每个文件一次 force、同目录一次 force、顺序、准备阶段失败、替换失败、目录 force 失败、原子替换不支持、检查点失败保持、后续成功恢复、队列满。完整模组隔离服验证注入真正生效、gzip/NBT 可读、真实 FTB ZIP、世界失败禁止 ZIP、dirty 重试、手动 flush、关服和 ZIP 还原。采样验证写入不再携带 O_SYNC 并对比同样文件的同步调用次数。

独立本地 Git 仓库保留 v1.1.0 基线。README、架构、实验记录、失败边界、构建运行、兼容性、部署回退、CHANGELOG、MIT LICENSE 统一整理。build.py 改为容器/服务器路径可配置；不提交产物、原始日志、真实世界文件或身份信息。未请求远程发布。
