package local.asyncplayersave.mixin;
import local.asyncplayersave.AsyncPlayerSave;
import net.minecraft.server.MinecraftServer;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(MinecraftServer.class)
abstract class ServerMixin {
 @Inject(method="tickServer",at=@At("HEAD"))
 private void local$notifications(BooleanSupplier supplier,CallbackInfo ci){AsyncPlayerSave.drainNotifications();}
 @Inject(method="saveAllChunks",at=@At("RETURN"))
 private void local$shutdownFlush(boolean silent,boolean flush,boolean force,CallbackInfoReturnable<Boolean> ci){if(flush||AsyncPlayerSave.stopping){AsyncPlayerSave.flushPlayers();AsyncPlayerSave.flushWorld();}}
 @Inject(method="stopServer",at=@At("HEAD"))
 private void local$stop(CallbackInfo ci){AsyncPlayerSave.beginShutdown();}
 @Inject(method="stopServer",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;close()V"))
 private void local$beforeUnlock(CallbackInfo ci){AsyncPlayerSave.flushPlayers();AsyncPlayerSave.flushWorld();}
 @Shadow public abstract boolean saveEverything(boolean silent,boolean flush,boolean force);
 @Redirect(method="tickServer",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;saveEverything(ZZZ)Z"))
 private boolean local$periodic(MinecraftServer server,boolean silent,boolean flush,boolean force){
  AsyncPlayerSave.PERIODIC.set(true);
  try{return saveEverything(silent,flush,force);}finally{AsyncPlayerSave.PERIODIC.remove();}
 }
 @Inject(method="saveEverything",at=@At("RETURN"))
 private void local$flush(boolean silent,boolean flush,boolean force,CallbackInfoReturnable<Boolean> ci){
  if(!AsyncPlayerSave.isBackupCapture()&&(flush||!AsyncPlayerSave.PERIODIC.get())){AsyncPlayerSave.flushPlayers();AsyncPlayerSave.flushWorld();}
 }
}
