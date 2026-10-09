package local.asyncplayersave;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.nbt.CompoundTag;

/** Owns FAWS SavedData/level.dat completion, including errors the old FAWS tasks swallowed. */
public final class WorldSaveCoordinator {
    private static volatile WorldSaveQueue queue;

    private static synchronized WorldSaveQueue queue() {
        if (queue != null) return queue;
        try {
            Class<?> mod = Class.forName("com.fastasyncworldsave.FastAsyncWorldSave");
            Executor executor = (Executor) mod.getField("threadPool").get(null);
            if (executor == null) throw new IllegalStateException("FAWS executor is not initialized");
            queue = new WorldSaveQueue(executor, 256);
            return queue;
        } catch (ClassNotFoundException absent) { return null; }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot access FAWS 2.6 executor", error); }
    }

    public static boolean available() { return queue() != null; }
    public static CompletableFuture<Void> checkpoint() {
        WorldSaveQueue current = queue();
        return current == null ? CompletableFuture.completedFuture(null) : current.checkpoint();
    }
    static void reset() { queue = null; }
    public static void captureFailed(Path path, Throwable error) {
        WorldSaveQueue current = queue();
        if (current != null) current.recordFailure(key(path), error);
        AsyncPlayerSave.LOG.error("World NBT capture failed; data stays dirty: {}", path, error);
    }
    private static String key(Path path) { return path.toAbsolutePath().normalize().toString(); }

    public static void submit(Map<Path, CompoundTag> snapshots, Set<Path> keepOld, Runnable restoreDirty) {
        WorldSaveQueue current = Objects.requireNonNull(queue(), "FAWS required for world saves");
        Map<Path, AtomicSave.Writer> writers = new LinkedHashMap<>();
        snapshots.forEach((path, snapshot) -> writers.put(path, temporary -> NbtWrites.write(snapshot, temporary)));
        List<String> keys = snapshots.keySet().stream().map(WorldSaveCoordinator::key).toList();
        long enqueued = System.nanoTime();
        current.submit(keys, () -> {
            long started = System.nanoTime();
            IOException last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    AtomicSave.writeBatch(writers, keepOld);
                    if (Boolean.getBoolean("local.asyncplayersave.logTimings")) {
                        AsyncPlayerSave.LOG.info("World save durable: files={}, queueMs={}, ioMs={}, targets={}",
                            writers.size(), TimeUnit.NANOSECONDS.toMillis(started-enqueued),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started), writers.keySet());
                    }
                    return;
                } catch (IOException error) { last = error; if (error instanceof AtomicSave.PublicationException) break; }
            }
            throw Objects.requireNonNull(last);
        }, () -> {
            AsyncPlayerSave.LOG.error("World save failed; backup will fail and SavedData will be marked dirty: {}", snapshots.keySet());
            restoreDirty.run();
        }).whenComplete((ignored, error) -> {
            if (error != null) AsyncPlayerSave.LOG.error("World durability failure: {}", snapshots.keySet(), error);
        });
    }
}
