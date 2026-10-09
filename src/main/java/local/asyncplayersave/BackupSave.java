package local.asyncplayersave;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/** The save future covers capture AND IO, without blocking the server thread. */
public final class BackupSave {
 public static CompletableFuture<Void> submit(Function<Runnable,CompletableFuture<Void>> main,Runnable save,Supplier<CompletableFuture<Void>> writes){
  var checkpoint=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
  return main.apply(()->SaveContext.capture(()->{
   save.run();
   // Capture failure-bearing IO futures before the server resumes other tasks.
   checkpoint.set(writes.get());
  })).thenCompose(ignored->checkpoint.get());
 }
}
