package local.asyncplayersave;
import java.util.concurrent.*;
final class SaveQueue implements AutoCloseable {
 interface Work {void run() throws Exception;}
 private final ConcurrentHashMap<String,CompletableFuture<Void>> pending=new ConcurrentHashMap<>();
 private final ThreadPoolExecutor executor;
 SaveQueue(String name){executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{Thread t=new Thread(r,name);t.setDaemon(false);return t;},new ThreadPoolExecutor.AbortPolicy());}
 CompletableFuture<Void> submit(String key,Work work){
  CompletableFuture<Void> f=new CompletableFuture<>();
  executor.execute(()->{try{work.run();f.complete(null);}catch(Throwable e){f.completeExceptionally(e);}});
  pending.put(key,f);
  return f;
 }
 void await(String key){CompletableFuture<Void> f=pending.get(key);if(f!=null){try{f.join();}finally{pending.remove(key,f);}}}
 void awaitAll(){
  CompletionException failure=null;
  for(String key:pending.keySet())try{await(key);}catch(CompletionException e){failure=e;}
  if(failure!=null)throw failure;
 }
 CompletableFuture<Void> checkpoint(){
  // Snapshot failure-bearing futures before later saves can replace their keys.
  var snapshot=new java.util.HashMap<>(pending);
  // Latest per-key futures cover earlier writes in this single FIFO worker.
  // No extra queue slot is needed when the IO queue is already full.
  return CompletableFuture.allOf(snapshot.values().toArray(CompletableFuture<?>[]::new))
   .whenComplete((ignored,error)->snapshot.forEach((key,future)->pending.remove(key,future)));
 }
 public void close(){executor.shutdown();try{awaitAll();}finally{boolean interrupted=false;while(!executor.isTerminated())try{executor.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}}
}
