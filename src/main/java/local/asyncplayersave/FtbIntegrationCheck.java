package local.asyncplayersave;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Explicitly enabled only on an independent validation world. */
final class FtbIntegrationCheck {
 static void run(MinecraftServer server){server.execute(()->start(server));}
 private static void start(MinecraftServer server){
  try{
   Class<?> handler=Class.forName("net.creeperhost.ftbbackups.BackupHandler");
   var player=FakePlayerFactory.getMinecraft(server.overworld());
   Class<?> worldMod=Class.forName("com.fastasyncworldsave.FastAsyncWorldSave");
   ExecutorService worldIO=(ExecutorService)worldMod.getField("threadPool").get(null);
   CountDownLatch gate=new CountDownLatch(1);
   worldIO.execute(()->{try{gate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
   CompletableFuture.delayedExecutor(3,TimeUnit.SECONDS).execute(gate::countDown);
   // getPlayers() is deliberately an unmodifiable view in this runtime.
   var playersField=net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
   playersField.setAccessible(true);
   @SuppressWarnings("unchecked") var players=(java.util.List<net.minecraft.server.level.ServerPlayer>)playersField.get(server.getPlayerList());
   player.setHealth(6);players.add(player);
   long before=System.nanoTime();
   try{handler.getMethod("createBackup",MinecraftServer.class,boolean.class,String.class).invoke(null,server,false,"async-validation");}
   finally{players.remove(player);}
   long captureMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before);
   if(captureMs>2000)throw new IllegalStateException("FTB capture waited for held IO: "+captureMs+"ms");
   @SuppressWarnings("unchecked") CompletableFuture<Void> backup=(CompletableFuture<Void>)handler.getField("currentFuture").get(null);
   if(backup==null||backup.isDone())throw new IllegalStateException("Backup compressed before world IO gate released");
   AsyncPlayerSave.LOG.info("FTB CAPTURE PASS: server returned in {}ms while world IO held for 3s",captureMs);
   Thread verifier=new Thread(()->{
    try{
     server.submit(()->{}).get(2,TimeUnit.SECONDS);
     backup.get(30,TimeUnit.SECONDS);
     Path folder=Path.of("backups").toAbsolutePath();
     Path zip;
     try(var files=Files.list(folder)){zip=files.filter(p->p.toString().endsWith(".zip")).max(java.util.Comparator.comparing(Path::toString)).orElseThrow();}
     try(ZipFile archive=new ZipFile(zip.toFile())){
      var entry=archive.stream().filter(e->e.getName().endsWith("playerdata/"+player.getStringUUID()+".dat")).findFirst().orElseThrow();
      try(var input=archive.getInputStream(entry)){
       if(NbtIo.readCompressed(input,NbtAccounter.unlimitedHeap()).getFloat("Health")!=6)throw new IllegalStateException("Backup omitted new player snapshot");
      }
      if(archive.stream().noneMatch(e->e.getName().endsWith("level.dat")))throw new IllegalStateException("Missing world metadata");
     }
     server.submit(()->{for(var level:server.getAllLevels())if(level.noSave)throw new IllegalStateException("World save state not restored");}).get(2,TimeUnit.SECONDS);
     AsyncPlayerSave.LOG.info("FTB INTEGRATION PASS: ticks continued while IO held, ZIP contains new player snapshot and world metadata, saves restored");
     // An already completed IO error must be checked, rather than skipped by isDone().
     Thread.sleep(1100);
     long zipCount;
     try(var files=Files.list(folder)){zipCount=files.filter(p->p.toString().endsWith(".zip")).count();}
     server.submit(()->{
      try{
       var field=AsyncPlayerSave.class.getDeclaredField("queue");field.setAccessible(true);
       SaveQueue queue=(SaveQueue)field.get(null);
       queue.submit("ftb-failure-validation",()->{throw new java.io.IOException("INJECTED validation player IO failure");}).handle((value,error)->null).join();
       handler.getMethod("createBackup",MinecraftServer.class,boolean.class,String.class).invoke(null,server,false,"failure-validation");
      }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
     }).get(2,TimeUnit.SECONDS);
     long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
     boolean restored=false;
     while(System.nanoTime()<deadline){
      restored=!(boolean)handler.getMethod("isRunning").invoke(null)&&server.submit(()->{for(var level:server.getAllLevels())if(level.noSave)return false;return true;}).get(2,TimeUnit.SECONDS);
      if(restored)break;Thread.sleep(20);
     }
     if(!restored)throw new IllegalStateException("Failed backup did not terminate and restore saves");
     try(var files=Files.list(folder)){if(files.filter(p->p.toString().endsWith(".zip")).count()!=zipCount)throw new IllegalStateException("Failed IO still created a ZIP");}
     AsyncPlayerSave.LOG.info("FTB FAILURE PASS: injected player IO failure prevents ZIP creation and restores world saving");
    }catch(Throwable e){AsyncPlayerSave.LOG.error("FTB INTEGRATION FAILED",e);}
   },"ftb-validation");verifier.setDaemon(true);verifier.start();
  }catch(Throwable e){AsyncPlayerSave.LOG.error("FTB INTEGRATION FAILED",e);}
 }
 static void restore(MinecraftServer server){
  var player=FakePlayerFactory.getMinecraft(server.overworld());
  var storage=((local.asyncplayersave.mixin.ServerStorageAccessor)server).local$getPlayerStorage();
  if(storage.load(player).orElseThrow().getFloat("Health")!=6)throw new IllegalStateException("Restored player state differs from backup");
  AsyncPlayerSave.LOG.info("FTB RESTORE PASS: server booted from extracted FTB ZIP and loaded saved player health 6");
 }
}
