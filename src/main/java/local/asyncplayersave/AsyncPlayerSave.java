package local.asyncplayersave;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import java.io.File;import java.io.IOException;import java.nio.file.Path;import java.util.concurrent.*;
@Mod("local_async_player_save")
public final class AsyncPlayerSave {
 static final Logger LOG=LoggerFactory.getLogger("AsyncPlayerSave");
 public static final ThreadLocal<Boolean> PERIODIC=ThreadLocal.withInitial(()->false);
 private static volatile SaveQueue queue;
 public static volatile boolean stopping;
 private static final ConcurrentLinkedQueue<Runnable> notifications=new ConcurrentLinkedQueue<>();
 public AsyncPlayerSave(IEventBus bus){
  NeoForge.EVENT_BUS.addListener((ServerStartedEvent e)->{queue=new SaveQueue("async-player-save");stopping=false;if(Boolean.getBoolean("local.asyncplayersave.selftest"))IntegrationCheck.run(e.getServer());if(Boolean.getBoolean("local.asyncplayersave.ftbselftest"))FtbIntegrationCheck.run(e.getServer());if(Boolean.getBoolean("local.asyncplayersave.restoretest"))FtbIntegrationCheck.restore(e.getServer());LOG.info("Player autosave and FTB backup IO enabled for Minecraft 1.21.1; manual saves and shutdown use barriers");});
  NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e)->{beginShutdown();});
  NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e)->{try{flushWorld();SaveQueue q=queue;if(q!=null)q.close();}finally{queue=null;}});
 }
 public static boolean submit(Player player,File dir){
  SaveQueue q=queue;if(q==null||stopping||(!PERIODIC.get()&&!SaveContext.isBackup()))return false;
  // Capture mutable entity state on the server thread, then hand a private copy to IO.
  CompoundTag tag;
  try{tag=player.saveWithoutId(new CompoundTag()).copy();}catch(Exception e){if(SaveContext.isBackup())throw new CompletionException(e);LOG.warn("Snapshot failed; falling back to vanilla player save",e);return false;}
  String id=player.getStringUUID();
  MinecraftServer server=player.getServer();Path target=dir.toPath().resolve(id+".dat");
  try{q.submit(id,()->{
   IOException failure=null;
   for(int attempt=0;attempt<3;attempt++)try{
    AtomicSave.write(target,path->NbtIo.writeCompressed(tag,path));failure=null;break;
   }catch(IOException e){failure=e;}
   if(failure!=null){LOG.error("Player save failed after 3 attempts; previous file retained: {}",id,failure);throw failure;}
   // Preserve NeoForge save notification on the server thread, after the file is ready.
   notifications.add(()->EventHooks.firePlayerSavingEvent(player,dir,id));
  });return true;}
  catch(RejectedExecutionException e){if(SaveContext.isBackup())throw e;LOG.warn("Save queue full; draining before vanilla synchronous fallback");flushPlayers();return false;}
 }
 public static void awaitPlayer(Player player){SaveQueue q=queue;if(q!=null)try{q.await(player.getStringUUID());}catch(CompletionException e){LOG.error("Previous async player write failed; vanilla path will recover",e);}drainNotifications();}
 public static void flushPlayers(){SaveQueue q=queue;if(q!=null)try{q.awaitAll();}catch(CompletionException e){LOG.error("Async player save failure encountered at barrier",e);}drainNotifications();}
 public static void drainNotifications(){Runnable r;while((r=notifications.poll())!=null)try{r.run();}catch(Exception e){LOG.error("Player save event listener failed",e);}}
 public static void beginShutdown(){stopping=true;flushPlayers();SaveQueue q=queue;if(q!=null)try{q.close();}catch(CompletionException e){LOG.error("Player save failure while closing queue",e);}flushWorld();}
 public static boolean isBackupCapture(){return SaveContext.isBackup();}
 public static CompletableFuture<Void> backupCheckpoint(MinecraftServer server){
  SaveQueue q=queue;
  CompletableFuture<Void> players=q==null?CompletableFuture.completedFuture(null):q.checkpoint();
  // Saving-event callbacks belong on the server thread, before the world marker.
  return players.thenCompose(ignored->server.submit(AsyncPlayerSave::drainNotifications))
   .thenCompose(ignored->worldCheckpoint());
 }
 private static CompletableFuture<Void> worldCheckpoint(){
  CompletableFuture<Void> done=new CompletableFuture<>();
  try{
   Class<?> mod=Class.forName("com.fastasyncworldsave.FastAsyncWorldSave");
   ExecutorService executor=(ExecutorService)mod.getField("threadPool").get(null);
   executor.execute(()->done.complete(null));
  }catch(ClassNotFoundException e){done.complete(null);}
  catch(ReflectiveOperationException|RejectedExecutionException e){done.completeExceptionally(e);}
  return done;
 }
 public static void flushWorld(){
  // Existing Fast Async World Save uses one FIFO queue. A marker waits for all earlier writes.
  try{
   Class<?> mod=Class.forName("com.fastasyncworldsave.FastAsyncWorldSave");
   ExecutorService executor=(ExecutorService)mod.getField("threadPool").get(null);
   Future<?> marker=executor.submit(()->{});boolean interrupted=false;
   for(;;)try{marker.get();break;}catch(InterruptedException e){interrupted=true;}
   if(interrupted)Thread.currentThread().interrupt();
  }catch(ClassNotFoundException e){/* Optional companion mod. */}
  catch(ReflectiveOperationException|ExecutionException|RejectedExecutionException e){LOG.error("Could not drain Fast Async World Save queue",e);}
 }
}
