package local.asyncplayersave.mixin;
import local.asyncplayersave.AsyncPlayerSave;
import java.io.File;import net.minecraft.world.entity.player.Player;import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.*;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(PlayerDataStorage.class)
abstract class PlayerStorageMixin {
 @Shadow @Final private File playerDir;
 @Inject(method="save",at=@At("HEAD"),cancellable=true)
 private void local$save(Player player,CallbackInfo ci){
  if(AsyncPlayerSave.submit(player,playerDir))ci.cancel();else AsyncPlayerSave.awaitPlayer(player);
 }
 @Inject(method="load(Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;",at=@At("HEAD"))
 private void local$load(Player player,CallbackInfoReturnable<?> ci){AsyncPlayerSave.awaitPlayer(player);}
}
