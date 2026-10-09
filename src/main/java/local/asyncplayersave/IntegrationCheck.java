package local.asyncplayersave;
import net.minecraft.server.MinecraftServer;import net.minecraft.nbt.*;import net.minecraft.world.level.storage.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import local.asyncplayersave.mixin.ServerStorageAccessor;
/** Runs only when explicitly enabled on an isolated server with -Dlocal.asyncplayersave.selftest=true. */
final class IntegrationCheck {
 static void run(MinecraftServer server){
  var player=FakePlayerFactory.getMinecraft(server.overworld());
  var storage=((ServerStorageAccessor)server).local$getPlayerStorage();
  try{
   AsyncPlayerSave.PERIODIC.set(true);
   player.setHealth(17);storage.save(player);player.setHealth(12);storage.save(player);
  }finally{AsyncPlayerSave.PERIODIC.remove();}
  // Actual load mixin must wait for both writes, and latest snapshot must win.
  var loaded=storage.load(player).orElseThrow();
  if(loaded.getFloat("Health")!=12)throw new IllegalStateException("Player load barrier/order failed");
  try{
   var old=server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID()+".dat_old");
   if(NbtIo.readCompressed(old,NbtAccounter.unlimitedHeap()).getFloat("Health")!=17)throw new IllegalStateException("Previous player save not retained");
  }catch(java.io.IOException e){throw new IllegalStateException(e);}
  // Actual non-periodic save path must wait and write synchronously.
  player.setHealth(8);storage.save(player);
  if(storage.load(player).orElseThrow().getFloat("Health")!=8)throw new IllegalStateException("Synchronous fallback failed");
  AsyncPlayerSave.LOG.info("INTEGRATION PASS: actual NeoForge player snapshot, ordered async writes, load barrier, dat_old and manual save");
 }
}
