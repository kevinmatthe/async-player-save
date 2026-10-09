package local.asyncplayersave;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.*;
public final class BackupSaveTest {
 @SuppressWarnings("unchecked")
 static CompletableFuture<Void> submit(Function<Runnable,CompletableFuture<Void>> main,Runnable save,Supplier<CompletableFuture<Void>> writes){
  try{return (CompletableFuture<Void>)Class.forName("local.asyncplayersave.BackupSave").getDeclaredMethod("submit",Function.class,Runnable.class,Supplier.class).invoke(null,main,save,writes);}
  catch(ClassNotFoundException|NoSuchMethodException e){throw new AssertionError("FTB save future must extend through async IO completion",e);}
  catch(ReflectiveOperationException e){throw new AssertionError(e);}
 }
 static boolean inBackup(){try{return (boolean)Class.forName("local.asyncplayersave.SaveContext").getDeclaredMethod("isBackup").invoke(null);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
 @SuppressWarnings("unchecked")
 static CompletableFuture<Void> checkpoint(SaveQueue q){
  try{return (CompletableFuture<Void>)SaveQueue.class.getDeclaredMethod("checkpoint").invoke(q);}
  catch(ReflectiveOperationException e){throw new AssertionError("Save queue needs an asynchronous completion checkpoint",e);}
 }
 public static void main(String[] args)throws Exception{
  ExecutorService main=Executors.newSingleThreadExecutor();SaveQueue io=new SaveQueue("backup-test-io");CountDownLatch gate=new CountDownLatch(1);
  try{
   io.submit("player",()->gate.await());
   CompletableFuture<Void> backup=submit(r->CompletableFuture.runAsync(r,main),()->{if(!inBackup())throw new AssertionError("Missing FTB context");},()->checkpoint(io));
   main.submit(()->{if(inBackup())throw new AssertionError("Backup context leaked into ticks");}).get(2,TimeUnit.SECONDS);
   if(backup.isDone())throw new AssertionError("Backup ready before writes finish");
   gate.countDown();backup.get(2,TimeUnit.SECONDS);
   CompletableFuture<Void> failed=submit(r->CompletableFuture.runAsync(r,main),()->{},()->CompletableFuture.failedFuture(new java.io.IOException("disk failure")));
   try{failed.get(2,TimeUnit.SECONDS);throw new AssertionError("Write failure must prevent compression");}catch(ExecutionException expected){if(!(expected.getCause() instanceof java.io.IOException))throw expected;}
   CompletableFuture<Void> captureFailed=submit(r->CompletableFuture.runAsync(r,main),()->{throw new IllegalStateException("capture failed");},()->{throw new AssertionError("Checkpoint after failed capture");});
   try{captureFailed.get(2,TimeUnit.SECONDS);throw new AssertionError("Capture failure hidden");}catch(ExecutionException expected){}
   main.submit(()->{if(inBackup())throw new AssertionError("Context leaked after exception");}).get(2,TimeUnit.SECONDS);
   io.submit("bad",()->{throw new java.io.IOException("queued failure");});
   try{checkpoint(io).get(2,TimeUnit.SECONDS);throw new AssertionError("Queue checkpoint hid failed write");}catch(ExecutionException expected){}
   checkpoint(io).get(2,TimeUnit.SECONDS);
   io.submit("racy",()->{throw new java.io.IOException("capture write failed");});
   CompletableFuture<Void> racy=submit(r->{r.run();try{io.await("racy");}catch(CompletionException expected){}return CompletableFuture.completedFuture(null);},()->{},()->checkpoint(io));
   try{racy.get(2,TimeUnit.SECONDS);throw new AssertionError("Checkpoint must snapshot failures before the main capture returns");}catch(ExecutionException expected){}
   SaveQueue full=new SaveQueue("full-backup-queue");CountDownLatch blocked=new CountDownLatch(1),started=new CountDownLatch(1);
   try{
    full.submit("running",()->{started.countDown();blocked.await();});started.await();
    for(int i=0;i<256;i++)full.submit("p"+i,()->{});
    CompletableFuture<Void> ready=checkpoint(full);
    if(ready.isDone())throw new AssertionError("Full queue checkpoint returned early");
    blocked.countDown();ready.get(2,TimeUnit.SECONDS);
   }finally{blocked.countDown();full.close();}
   System.out.println("PASS: FTB save releases server thread, waits for writes, propagates completed failures, restores context, supports full queues and failure recovery");
  }finally{gate.countDown();main.shutdownNow();try{io.close();}catch(CompletionException expected){}}
 }
}
