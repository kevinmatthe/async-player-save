package local.asyncplayersave.mixin;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import local.asyncplayersave.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dimension is one durability batch; mutable SavedData is only touched on the server thread. */
@Mixin(value = DimensionDataStorage.class, priority = 900)
abstract class DimensionDataStorageMixin {
    @Shadow @Final private Map<String, SavedData> cache;
    @Shadow @Final private HolderLookup.Provider registries;
    @Shadow protected abstract File getDataFile(String id);

    @Inject(method = "save", at = @At("HEAD"), cancellable = true, require = 1)
    private void local$durableBatch(CallbackInfo ci) {
        if (!WorldSaveCoordinator.available()) return;
        Map<Path, CompoundTag> snapshots = new LinkedHashMap<>();
        List<SavedData> selected = new ArrayList<>();
        for (var entry : cache.entrySet()) {
            SavedData data = entry.getValue();
            if (data == null || !data.isDirty()) continue;
            Path target = getDataFile(entry.getKey()).toPath();
            try {
                CompoundTag payload = data.save(new CompoundTag(), registries);
                if (payload == null) throw new IllegalStateException("SavedData returned null: " + entry.getKey());
                CompoundTag root = new CompoundTag();
                root.put("data", payload.copy());
                NbtUtils.addCurrentDataVersion(root);
                snapshots.put(target, root); selected.add(data);
            } catch (Exception error) { WorldSaveCoordinator.captureFailed(target, error); }
        }
        if (!snapshots.isEmpty()) {
            var server = ServerLifecycleHooks.getCurrentServer();
            Runnable restoreDirty = () -> {
                if (server == null) throw new IllegalStateException("No server to restore SavedData dirty state");
                server.execute(() -> selected.forEach(data -> data.setDirty(true)));
            };
            try {
                WorldSaveCoordinator.submit(snapshots, Set.of(), restoreDirty);
                selected.forEach(data -> data.setDirty(false));
            } catch (java.util.concurrent.RejectedExecutionException error) {
                snapshots.keySet().forEach(path -> WorldSaveCoordinator.captureFailed(path, error));
            }
        }
        ci.cancel();
    }
}
