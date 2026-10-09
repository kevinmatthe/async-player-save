# Durable save implementation plan

> Use superpowers:subagent-driven-development for the persistence helper and requesting-code-review for the completed implementation.

**Goal:** reduce repeated synchronous writes while making FTB completion reflect world durability failures.
**Architecture:** prepare/force temporary files, atomically publish and force each touched directory once per world batch; track per-path futures on the existing FAWS FIFO executor.
**Stack:** Java 21, Minecraft 1.21.1, NeoForge, Sponge Mixin, plain Java test mains.

## Task 1: persistence core
- [x] Add AtomicSaveBatchTest, demonstrate failure against current implementation, then implement AtomicSave.writeBatch(Map<Path, Writer>, Set<Path>). Retain write(Path,Writer) as keepOld=true convenience.
- [x] Add injectable Operations interface for real filesystem fault tests: forceFile(Path), forceDirectory(Path), move(Path,Path), preserveOld(Path,Path). Defaults use FileChannel.force(true), directory channel READ+force, atomic Files.move, and hardlink or forced-copy backup.
- [x] Test force and replacement ordering, one file force and one directory force, old-target preservation on prepare failure, post-publish directory failure propagation, temp cleanup and rejection of unsupported atomic move. Run existing AtomicSaveTest as regression.

## Task 2: world futures and mixins
- [x] Write WorldSaveQueueTest for queued completion, immutable checkpoint snapshots, failed checkpoints staying failed, recovery only by replacing the same key, rejection and capacity. Implement bounded WorldSaveQueue(Executor) with submit(Collection<String>,IOAction,Runnable) and checkpoint().
- [x] Add NbtWrites.write(CompoundTag,Path) using NbtIo.writeCompressed(tag, ordinary buffered OutputStream); read back with NbtIo.readCompressed in integration.
- [x] Add WorldSaveCoordinator to adapt FAWS executor and batch CompoundTag writes through AtomicSave; dirty restoration runs on server thread.
- [x] Add DimensionDataStorageMixin and LevelStorageMixin cancellation at HEAD, priority 900, require=1; copy tags on server thread. Leave original path when FAWS absent.
- [x] Update AsyncPlayerSave player writer, initialization and backup checkpoint to include fixed world future snapshot, preserving existing FAWS drain. Bump version to 1.2.0 and constrain optional FAWS compatibility.

## Task 3: portable build and documentation
- [x] Add configurable --container and --server-dir CLI/env settings; derive output version from metadata; run all Java test mains with exact Java 21 dependencies.
- [x] Document architecture, measured write sizes/latencies, what async fixes and what it does not, one-force approach, failure stages and limits. Include deployment/rollback steps, compatibility, privacy exclusions and no remote publishing claim.

## Task 4: isolated runtime proof and review
- [x] Start independent small world with full production mod set and disabled public endpoints; never inject failure into production.
- [x] Verify positive real FTB backup and newly saved NBT; inject real world persistence failure, assert no ZIP and world save flags restored, then recover and prove dirty data is retried.
- [x] Verify save-all flush, normal stop and restore an extracted successful archive. Capture syscall flags/counts for single-file vs per-batch directory force.
- [x] Request independent spec and code review, resolve findings, rerun affected tests, commit and integrate locally. Record artifact hash and validation evidence; do not deploy unverified code.
