# 验证和验收

## 自动回归

`build.py` 编译并运行所有 `src/test/java/local/asyncplayersave/*Test.java`：

- SaveQueueTest：玩家非阻塞提交、顺序、读取屏障、失败恢复和关闭。
- BackupSaveTest：FTB 捕获返回、IO 完成屏障、失败、上下文、满队列及竞态。
- AtomicSaveTest / AtomicSaveBatchTest：完整准备、文件和目录 force 的顺序/次数、旧版、硬链接回退、原子移动不支持、发布前/后失败、新目录重试、清理错误。
- WorldSaveQueueTest：固定检查点、失败持续可见、按路径恢复、dirty 回调、容量和拒绝。
- NbtWritesTest：随机大块 NBT，gzip 完整结束后一次文件 force、一次目录 force，内容一致。

注入文件系统操作只用于单元测试。运行时错误测试使用独立世界中的专用文件名，不改变系统存储属性。

## 完整模组隔离测试

准备独立 Minecraft 目录与小世界，复制配置并调整：回环监听、独立端口、RCON 关闭、FTB 输出只在该目录内、定时备份禁用、BlueMap/导出器等附加监听服务关闭或使用独立端口。第三方 mods 和 libraries 可以只读共享；补丁 JAR 单独复制。不要共享正式世界、正式 FTB 目录或插件状态。

以 Java 21 启动，添加：

```text
-Dlocal.asyncplayersave.ftbselftest=true
-Dlocal.asyncplayersave.worldselftest=true
-Dlocal.asyncplayersave.logTimings=true
```

测试依次验证：

1. 世界队列持有 3 秒时，FTB 捕获迅速返回，主线程仍能响应。
2. 真实 ZIP 包含新 FakePlayer health=6 和 level.dat，世界保存开关恢复。
3. 已完成的玩家错误 future 阻止新 ZIP，开关恢复。
4. 世界专用目标路径故意为目录，真实文件发布失败；阻止 ZIP、恢复 dirty、失败检查点仍可见。
5. 删除测试冲突目录，后续保存自动重试 dirty，真实 ZIP 中 SavedData value=7，检查点恢复。

预期日志包含 FTB CAPTURE / INTEGRATION / FAILURE PASS、WORLD FAILURE / RECOVERY PASS。任何 `INTEGRATION FAILED` 或 Mixin 应用错误都不能视为通过。

随后执行 `save-all flush` 并正常 `stop`，等待进程退出；确认队列完成、没有锁持有期间遗漏的后续写入。

## 还原

按修改时间选择最后一个成功 ZIP，而不是按未补零文件名的字典序。提取世界到另一独立目录，使用相同模组集，以 Java 21 启动并添加：

```text
-Dlocal.asyncplayersave.restoretest=true
-Dlocal.asyncplayersave.worldrestoretest=true
```

预期 FTB RESTORE PASS（FakePlayer health=6）与 WORLD RESTORE PASS（SavedData value=7）；再次正常停止。

## 系统调用和性能

可在隔离进程中低负载追踪 openat、write/pwrite/writev、fsync/fdatasync 和 close，确认普通输出 FD 没有 O_SYNC，完成后有文件和目录持久化调用。只追踪保存线程，避免记录 ZIP/区块大量写入造成额外负载。

正式服性能应记录每轮排队和 ioMs、目标文件数量、同一文件字节数以及 TRIM、磁盘 flush 状态。不得将队列等待、压缩总时间、主线程采样时间混为文件实际持久化耗时。原始产物与日志存放 build/ 并由 Git 忽略。

## 2026-10-09 的 1.2.0 实测

使用 Java 21.0.12、NeoForge 21.1.251 和完整目标模组集，独立小世界验证通过：

- 六组回归测试全部通过，JAR 类文件版本为 65（Java 21）。
- 主线程 FTB 捕获为 20ms，世界 IO 被人为持有 3 秒期间仍继续 tick。
- 真实 FTB 正常备份、玩家错误阻止 ZIP、世界发布失败阻止 ZIP、dirty 自动恢复后重试成功。
- 从成功 FTB ZIP 解压世界，以 Java 21 启动后玩家 health=6、世界 SavedData value=7 均验证通过。隔离服执行 `save-all flush` 和控制台 `stop` 正常退出。
- 对一次完整 14 文件保存做内核系统调用追踪：14 次文件 fsync、5 次目录 fsync；28 次带目标路径的数据 write 均没有 O_SYNC。
- gzip 头与内容仍可分段输出，但各段不再等待同步持久化。

日志、原始 syscall trace 和带 SHA-256 的 `validation-summary.json` 保存在本地 `build/`，不提交世界或完整原始日志。以上为隔离服功能和调用路径验证，正式服尚未替换 1.2.0，也不据此宣称生产提速倍数。

## 未验证的边界

未实施真实断电/设备掉线；文件系统正确履行 force 是前提。批次不是跨文件事务；其他模组独立写入、区块 region 和硬件故障另行评估。只有隔离服通过不代表正式服已替换或性能已改善。
