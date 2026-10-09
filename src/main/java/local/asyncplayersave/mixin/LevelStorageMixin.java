package local.asyncplayersave.mixin;

import java.nio.file.Path;
import java.util.*;
import local.asyncplayersave.WorldSaveCoordinator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs after FAWS' overwrite and replaces only its metadata task. */
@Mixin(value = LevelStorageSource.LevelStorageAccess.class, priority = 900)
abstract class LevelStorageMixin {
    @Shadow @Final public LevelStorageSource.LevelDirectory levelDirectory;
    @Inject(method = "saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void local$durableLevel(CompoundTag tag, CallbackInfo ci) {
        if (!WorldSaveCoordinator.available()) return;
        Path target = levelDirectory.dataFile();
        try { WorldSaveCoordinator.submit(Map.of(target, tag.copy()), Set.of(target), () -> {}); }
        catch (Exception error) { WorldSaveCoordinator.captureFailed(target, error); }
        ci.cancel();
    }
}
