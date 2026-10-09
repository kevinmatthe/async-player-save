package local.asyncplayersave;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class AtomicSave {
 private static final ConcurrentMap<Path,Object> pendingDirectorySyncs=new ConcurrentHashMap<>();
 static final class PublicationException extends IOException {
  PublicationException(IOException cause){super("Save publication failed: "+cause.getMessage(),cause);}
 }
 interface Writer {void write(Path path)throws IOException;}

 /** Real filesystem operations; tests override individual failure boundaries. */
 static class Operations {
  void forceFile(Path path)throws IOException {
   try(FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}
  }
  void forceDirectory(Path path)throws IOException {
   try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}
  }
  void atomicMove(Path source,Path target)throws IOException {
   Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  }
  void createLink(Path link,Path existing)throws IOException {Files.createLink(link,existing);}
  void deleteIfExists(Path path)throws IOException {Files.deleteIfExists(path);}
  void copy(Path source,Path target)throws IOException {Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);}
 }
 private record Prepared(Path temporary,Path target){}

 static void write(Path target,Writer writer)throws IOException {
  writeBatch(Map.of(target,writer),Set.of(target));
 }
 static void writeBatch(Map<Path,Writer> writers,Set<Path> keepOld)throws IOException {
  writeBatch(writers,keepOld,new Operations());
 }

 /** Prepare and force everything before publishing. Publication is not a multi-file transaction. */
 static void writeBatch(Map<Path,Writer> writers,Set<Path> keepOld,Operations operations)throws IOException {
  List<Path> temporaryFiles=new ArrayList<>();
  List<Prepared> files=new ArrayList<>(),backups=new ArrayList<>();
  Set<Path> directories=new LinkedHashSet<>(),oldTargets=new HashSet<>();
  for(Path target:keepOld)oldTargets.add(target.toAbsolutePath().normalize());
  Throwable failure=null;
  boolean publishing=false;
  try {
   for(var entry:writers.entrySet()){
    Path target=entry.getKey().toAbsolutePath().normalize(),parent=target.getParent();
    createDirectories(parent,directories);
    directories.add(parent);
    Path temporary=Files.createTempFile(parent,target.getFileName()+"-", ".tmp");
    temporaryFiles.add(temporary);
    entry.getValue().write(temporary);
    operations.forceFile(temporary);
    files.add(new Prepared(temporary,target));
   }
   for(Prepared file:files){
    Path target=file.target();
    if(!oldTargets.contains(target)||!Files.exists(target))continue;
    Path backup=Files.createTempFile(target.getParent(),target.getFileName()+"-backup-", ".tmp");
    temporaryFiles.add(backup);
    Files.delete(backup);
    try {operations.createLink(backup,target);}
    catch(UnsupportedOperationException unsupported){copyBackup(target,backup,operations);}
    catch(FileSystemException unsupported){
     if(!unsupportedLink(unsupported))throw unsupported;
     copyBackup(target,backup,operations);
    }
    backups.add(new Prepared(backup,target.resolveSibling(target.getFileName()+"_old")));
   }
   Map<Path,Object> pending=new HashMap<>();
   pendingDirectorySyncs.forEach((directory,generation)->{
    if(files.stream().anyMatch(file->file.target().getParent().startsWith(directory))){
     pending.put(directory,generation);directories.add(directory);
    }
   });
   for(Prepared backup:backups)operations.atomicMove(backup.temporary(),backup.target());
   publishing=!files.isEmpty();
   for(Prepared file:files)operations.atomicMove(file.temporary(),file.target());
   // Sync children before parents, including the names of directories created above.
   List<Path> syncOrder=new ArrayList<>(directories);
   syncOrder.sort(Comparator.comparingInt(Path::getNameCount).reversed());
   for(Path directory:syncOrder){
    operations.forceDirectory(directory);
    Object generation=pending.get(directory);
    if(generation!=null)pendingDirectorySyncs.remove(directory,generation);
   }
  }catch(IOException e){
   IOException reported=publishing?new PublicationException(e):e;
   failure=reported;throw reported;
  }catch(RuntimeException|Error e){failure=e;throw e;}
  finally {
   Throwable cleanupFailure=null;
   for(Path temporary:temporaryFiles){
    try{operations.deleteIfExists(temporary);}
    catch(IOException|RuntimeException|Error e){
     if(cleanupFailure==null)cleanupFailure=e;else if(cleanupFailure!=e)cleanupFailure.addSuppressed(e);
    }
   }
   if(cleanupFailure!=null){
    if(failure!=null){if(failure!=cleanupFailure)failure.addSuppressed(cleanupFailure);}
    else if(cleanupFailure instanceof IOException e)throw publishing?new PublicationException(e):e;
    else if(cleanupFailure instanceof RuntimeException e)throw e;
    else throw (Error)cleanupFailure;
   }
  }
 }
 private static void copyBackup(Path target,Path backup,Operations operations)throws IOException {
  operations.copy(target,backup);
  operations.forceFile(backup);
 }
 private static boolean unsupportedLink(FileSystemException exception){
  String reason=exception.getReason();
  if(reason==null)return false;
  reason=reason.toLowerCase(Locale.ROOT);
  return reason.contains("not supported")||reason.contains("unsupported")||reason.contains("not implemented");
 }
 private static void createDirectories(Path directory,Set<Path> touched)throws IOException {
  List<Path> missing=new ArrayList<>();
  for(Path path=directory;path!=null&&!Files.exists(path);path=path.getParent())missing.add(path);
  for(int i=missing.size()-1;i>=0;i--){
   Path path=missing.get(i);
   try{Files.createDirectory(path);}catch(FileAlreadyExistsException concurrent){if(!Files.isDirectory(path))throw concurrent;}
   if(path.getParent()!=null)pendingDirectorySyncs.put(path.getParent(),new Object());
   touched.add(path);if(path.getParent()!=null)touched.add(path.getParent());
  }
 }
}
