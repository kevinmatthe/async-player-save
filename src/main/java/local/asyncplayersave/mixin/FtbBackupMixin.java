package local.asyncplayersave.mixin;
import local.asyncplayersave.BackupSave;
import local.asyncplayersave.AsyncPlayerSave;
import net.minecraft.server.MinecraftServer;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Call sites verified against the unmodified FTB Backups 2 1.0.28 JAR. */
@Pseudo
@Mixin(targets="net.creeperhost.ftbbackups.BackupHandler",remap=false)
abstract class FtbBackupMixin {
 @Redirect(method="createBackup(Lnet/minecraft/server/MinecraftServer;ZLjava/lang/String;)V",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"),require=1)
 private static CompletableFuture<Void> local$save(MinecraftServer server,Runnable save){
  return BackupSave.submit(server::submit,save,()->AsyncPlayerSave.backupCheckpoint(server));
 }
 @Redirect(method="lambda$createBackup$1",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;isDone()Z"),require=1)
 private static boolean local$alwaysCheckFailure(CompletableFuture<?> save){
  // FTB skips get() when isDone(): that would also skip a completed exception.
  return false;
 }
}
