package local.asyncplayersave;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Real world publication failure, enabled only after the isolated FTB test finishes. */
final class WorldIntegrationCheck {
    private static final String ID = "local_durable_validation";
    private static final class Probe extends SavedData {
        int value;
        Probe(int value) { this.value = value; }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            tag.putInt("value", value); return tag;
        }
    }
    static void run(MinecraftServer server) {
        Thread verifier = new Thread(() -> {
            try {
                Class<?> handler = Class.forName("net.creeperhost.ftbbackups.BackupHandler");
                Path folder = Path.of("backups").toAbsolutePath();
                Path target = server.getWorldPath(LevelResource.ROOT).resolve("data/" + ID + ".dat");
                Probe probe = new Probe(11);
                long before = zipCount(folder);
                Thread.sleep(1100);
                server.submit(() -> {
                    try {
                        if (Files.exists(target)) throw new IllegalStateException("World failure fixture already exists");
                        Files.createDirectories(target.getParent()); Files.createDirectory(target);
                        probe.setDirty(true); server.overworld().getDataStorage().set(ID, probe);
                        handler.getMethod("createBackup", MinecraftServer.class, boolean.class, String.class)
                            .invoke(null, server, false, "world-failure-validation");
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }).get(2, TimeUnit.SECONDS);
                awaitRestored(server, handler);
                if (zipCount(folder) != before) throw new IllegalStateException("World failure created ZIP");
                if (!server.submit(probe::isDirty).get(2, TimeUnit.SECONDS)) throw new IllegalStateException("Failed world data not marked dirty");
                try { WorldSaveCoordinator.checkpoint().join(); throw new IllegalStateException("World failure forgotten"); }
                catch (CompletionException expected) {}
                AsyncPlayerSave.LOG.info("WORLD FAILURE PASS: real file replacement error prevents ZIP, restores saving and dirty state, remains visible at checkpoint");
                Thread.sleep(1100);
                server.submit(() -> {
                    try {
                        Files.delete(target); probe.value = 7;
                        // Do not set dirty here: this must use the failure-restored flag.
                        handler.getMethod("createBackup", MinecraftServer.class, boolean.class, String.class)
                            .invoke(null, server, false, "world-recovery-validation");
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }).get(2, TimeUnit.SECONDS);
                awaitRestored(server, handler);
                if (zipCount(folder) != before + 1) throw new IllegalStateException("Repaired world save did not create ZIP");
                WorldSaveCoordinator.checkpoint().join();
                Path archive;
                try (var files = Files.list(folder)) {
                    archive = files.filter(p -> p.toString().endsWith(".zip"))
                        .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified())).orElseThrow();
                }
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    var entry = zip.stream().filter(e -> e.getName().endsWith("data/" + ID + ".dat")).findFirst().orElseThrow();
                    try (var input = zip.getInputStream(entry)) {
                        if (NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap()).getCompound("data").getInt("value") != 7)
                            throw new IllegalStateException("World retry ZIP contains stale NBT");
                    }
                }
                AsyncPlayerSave.LOG.info("WORLD RECOVERY PASS: dirty retry persisted fresh NBT value 7, FTB ZIP readable, durability checkpoint recovered");
            } catch (Throwable error) { AsyncPlayerSave.LOG.error("WORLD INTEGRATION FAILED", error); }
        }, "world-validation");
        verifier.setDaemon(true); verifier.start();
    }
    private static long zipCount(Path folder) throws java.io.IOException {
        try (var files = Files.list(folder)) { return files.filter(p -> p.toString().endsWith(".zip")).count(); }
    }
    private static void awaitRestored(MinecraftServer server, Class<?> handler) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (!(boolean) handler.getMethod("isRunning").invoke(null)
                    && server.submit(() -> { for (var level : server.getAllLevels()) if (level.noSave) return false; return true; }).get(2, TimeUnit.SECONDS)) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("World validation backup failed to terminate/restore saving");
    }
    static void restore(MinecraftServer server) {
        try {
            Path target = server.getWorldPath(LevelResource.ROOT).resolve("data/" + ID + ".dat");
            if (NbtIo.readCompressed(target, NbtAccounter.unlimitedHeap()).getCompound("data").getInt("value") != 7)
                throw new IllegalStateException("Restored world probe differs");
            AsyncPlayerSave.LOG.info("WORLD RESTORE PASS: extracted backup world booted and saved NBT value 7 is readable");
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
