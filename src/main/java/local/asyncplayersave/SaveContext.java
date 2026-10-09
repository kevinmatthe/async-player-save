package local.asyncplayersave;

/** Only the server-thread capture phase of an FTB backup has this context. */
final class SaveContext {
 private static final ThreadLocal<Boolean> BACKUP=ThreadLocal.withInitial(()->false);
 static boolean isBackup(){return BACKUP.get();}
 static void capture(Runnable save){
  boolean previous=BACKUP.get();BACKUP.set(true);
  try{save.run();}finally{if(previous)BACKUP.set(true);else BACKUP.remove();}
 }
}
