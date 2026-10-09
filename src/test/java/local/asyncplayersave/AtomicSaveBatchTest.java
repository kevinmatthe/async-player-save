package local.asyncplayersave;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class AtomicSaveBatchTest {
 static class Recording extends AtomicSave.Operations {
  final List<String> events = new ArrayList<>();
  int forces, moves, directories, links, copies;
  int failForce, failMove, failDirectory;
  boolean unsupportedMove, unsupportedLink, failCleanup, linkIoError;
  @Override void deleteIfExists(Path p)throws IOException {
   if(failCleanup)throw new IOException("cleanup");super.deleteIfExists(p);
  }
  @Override void forceFile(Path p)throws IOException {
   events.add("file"); if (++forces == failForce) throw new IOException("file force"); super.forceFile(p);
  }
  @Override void atomicMove(Path a,Path b)throws IOException {
   events.add("move"); if (++moves == failMove) throw new IOException("move");
   if(unsupportedMove)throw new AtomicMoveNotSupportedException(a.toString(),b.toString(),"injected");
   super.atomicMove(a,b);
  }
  @Override void forceDirectory(Path p)throws IOException {
   events.add("directory:"+p); if(++directories == failDirectory)throw new IOException("directory force"); super.forceDirectory(p);
  }
  @Override void createLink(Path a,Path b)throws IOException {
   links++; if(linkIoError)throw new FileSystemException(a.toString(),b.toString(),"Permission denied"); if(unsupportedLink)throw new UnsupportedOperationException("hardlink unsupported"); super.createLink(a,b);
  }
  @Override void copy(Path a,Path b)throws IOException {copies++;super.copy(a,b);}
 }
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static Path directory()throws IOException{return Files.createTempDirectory("atomic-batch-");}
 static Map<Path,AtomicSave.Writer> writers(Path... paths){
  Map<Path,AtomicSave.Writer> result=new LinkedHashMap<>();
  for(Path p:paths)result.put(p,t->Files.writeString(t,"new-"+p.getFileName()));return result;
 }
 static void failure(Map<Path,AtomicSave.Writer> w,Set<Path> old,Recording op)throws Exception {
  try{AtomicSave.writeBatch(w,old,op);throw new AssertionError("failure hidden");}catch(IOException expected){}
 }
 static void noTemps(Path d)throws IOException {
  try(var s=Files.walk(d)){check(s.noneMatch(p->p.getFileName().toString().endsWith(".tmp")),"temporary files leaked");}
 }
 static void successfulBatch()throws Exception {
  Path d=directory(),a=d.resolve("player.dat"),b=d.resolve("saved.dat");Files.writeString(a,"previous");
  Recording op=new Recording();AtomicSave.writeBatch(writers(a,b),Set.of(a),op);
  check(op.forces==2,"one force per new file");check(op.directories==1,"one shared parent force");
  check(op.events.indexOf("move")>op.events.lastIndexOf("file"),"all files forced before publishing");
  check(op.events.getLast().startsWith("directory:"),"directory force must finish batch");
  check(Files.readString(a).equals("new-player.dat"),"new contents");
  check(Files.readString(a.resolveSibling("player.dat_old")).equals("previous"),"backup contents");
  check(!Files.exists(b.resolveSibling("saved.dat_old")),"SavedData must not gain backup");
  check(op.links==1&&op.copies==0,"hardlink preferred");noTemps(d);
 }
 static void preparationFailures()throws Exception {
  for(boolean writerFailure:new boolean[]{true,false}){
   Path d=directory(),a=d.resolve("a"),b=d.resolve("b"),old=d.resolve("a_old");
   Files.writeString(a,"a-old");Files.writeString(b,"b-old");Files.writeString(old,"older");
   Map<Path,AtomicSave.Writer>w=writers(a,b);Recording op=new Recording();
   if(writerFailure)w.put(b,t->{Files.writeString(t,"broken");throw new IOException("writer");});else op.failForce=2;
   failure(w,Set.of(a),op);check(op.moves==0,"no publication after preparation error");
   check(Files.readString(a).equals("a-old")&&Files.readString(b).equals("b-old"),"targets preserved");
   check(Files.readString(old).equals("older"),"old backup preserved");noTemps(d);
  }
 }
 static void publicationFailures()throws Exception {
  for(boolean unsupported:new boolean[]{false,true}){
   Path d=directory(),a=d.resolve("a");Files.writeString(a,"old");Recording op=new Recording();
   op.failMove=unsupported?0:2;op.unsupportedMove=unsupported;
   failure(writers(a),Set.of(a),op);check(Files.readString(a).equals("old"),"failed atomic replacement preserves live target");
   if(!unsupported)check(Files.readString(d.resolve("a_old")).equals("old"),"prior save retained after failed replacement");
   noTemps(d);
  }
 }
 static void directoryFailure()throws Exception {
  Path d=directory(),a=d.resolve("a");Files.writeString(a,"old");Recording op=new Recording();op.failDirectory=1;
  failure(writers(a),Set.of(a),op);check(Files.readString(a).equals("new-a"),"publish precedes directory failure");
  check(Files.readString(d.resolve("a_old")).equals("old"),"backup remains");noTemps(d);
 }
 static void backupFallback()throws Exception {
  Path d=directory(),a=d.resolve("a");Files.writeString(a,"old");Recording op=new Recording();op.unsupportedLink=true;
  AtomicSave.writeBatch(writers(a),Set.of(a),op);check(op.copies==1&&op.forces==2,"copied backup also forced");
  check(Files.readString(d.resolve("a_old")).equals("old"),"copied backup contents");noTemps(d);
  op=new Recording();op.unsupportedLink=true;op.failForce=2;
  failure(writers(a),Set.of(a),op);check(op.moves==0,"backup force failure prevents publish");
  check(Files.readString(d.resolve("a_old")).equals("old"),"backup force failure preserves older backup");noTemps(d);
 }
 static void cleanupAndLinkErrors()throws Exception {
  Path d=directory(),a=d.resolve("a"),old=d.resolve("a_old");
  Files.writeString(a,"old");Files.writeString(old,"older");Recording op=new Recording();op.linkIoError=true;
  failure(writers(a),Set.of(a),op);
  check(op.copies==0&&op.moves==0,"real hardlink IO errors must propagate");
  check(Files.readString(old).equals("older"),"failed backup preparation preserves old backup");noTemps(d);
  op=new Recording();op.failCleanup=true;
  try{AtomicSave.writeBatch(Map.of(a,t->{throw new IOException("original writer");}),Set.of(a),op);throw new AssertionError("failure hidden");}
  catch(IOException e){check(e.getMessage().equals("original writer"),"cleanup masked original");check(e.getSuppressed().length==1,"cleanup error not suppressed");}
  try(var paths=Files.list(d)){for(Path p:paths.toList())if(p.getFileName().toString().endsWith(".tmp"))Files.delete(p);}
  noTemps(d);
 }
 static void retryDirectoryDurability()throws Exception {
  Path d=directory(),nested=d.resolve("one/two"),a=nested.resolve("a");Recording failed=new Recording();failed.failForce=1;
  failure(writers(a),Set.of(),failed);check(failed.directories==0,"preparation failure synced directories unexpectedly");
  Path other=directory();Recording unrelated=new Recording();AtomicSave.writeBatch(writers(other.resolve("a")),Set.of(),unrelated);
  check(unrelated.directories==1,"unrelated batch forced stale ancestors");
  Recording retry=new Recording();AtomicSave.writeBatch(writers(a),Set.of(),retry);
  check(retry.events.stream().filter(e->e.startsWith("directory:")).toList().equals(List.of("directory:"+nested,"directory:"+d.resolve("one"),"directory:"+d)),"retry lost newly created ancestor durability");
 }
 static void publicationException()throws Exception {
  for(boolean directoryError:new boolean[]{true,false}){
   Path d=directory(),a=d.resolve("a");Files.writeString(a,"old");Recording op=new Recording();
   if(directoryError)op.failDirectory=1;else op.failMove=2;
   try{AtomicSave.writeBatch(writers(a),Set.of(a),op);throw new AssertionError("failure hidden");}
   catch(IOException e){
    check(e instanceof AtomicSave.PublicationException,"publication failure not distinguishable");
    check(e.getCause() instanceof IOException&&e.getCause().getMessage().equals(directoryError?"directory force":"move"),"publication cause missing");
   }
   check(Files.readString(d.resolve("a_old")).equals("old"),"publication failure lost backup");noTemps(d);
  }
 }
 static void publicationCleanupException()throws Exception {
  Path d=directory();Recording op=new Recording();op.failCleanup=true;
  try{AtomicSave.writeBatch(writers(d.resolve("a")),Set.of(),op);throw new AssertionError("failure hidden");}
  catch(IOException e){check(e instanceof AtomicSave.PublicationException&&e.getCause().getMessage().equals("cleanup"),"post-publication cleanup error not wrapped");}
  noTemps(d);
 }
 static void uncheckedCleanupFailures()throws Exception {
  for(boolean error:new boolean[]{false,true})for(boolean primary:new boolean[]{true,false}){
   Path d=directory(),a=d.resolve("a"),b=d.resolve("b");
   Throwable cleanup=error?new AssertionError("cleanup error"):new IllegalStateException("cleanup runtime");
   IOException original=new IOException("original writer");List<Path> attempts=new ArrayList<>();
   Recording op=new Recording(){
    @Override void deleteIfExists(Path p)throws IOException {
     attempts.add(p);
     if(attempts.size()==1){if(cleanup instanceof Error e)throw e;throw (RuntimeException)cleanup;}
     super.deleteIfExists(p);
    }
   };
   Map<Path,AtomicSave.Writer>w=writers(a,b);
   if(primary)w.put(b,t->{throw original;});
   try{AtomicSave.writeBatch(w,Set.of(),op);throw new AssertionError("failure hidden");}
   catch(Throwable actual){
    check(actual==(primary?original:cleanup),"unchecked cleanup masked primary or changed failure type");
    if(primary)check(actual.getSuppressed().length==1&&actual.getSuppressed()[0]==cleanup,"unchecked cleanup not suppressed");
   }
   check(attempts.size()==2,"unchecked cleanup stopped later cleanup");
   check(!Files.exists(attempts.get(1)),"later temporary file not cleaned");
   Files.deleteIfExists(attempts.get(0));noTemps(d);
  }
 }
 static void newDirectories()throws Exception {
  Path d=directory(),nested=d.resolve("one/two"),a=nested.resolve("a"),b=d.resolve("b");Recording op=new Recording();
  AtomicSave.writeBatch(writers(a,b),Set.of(),op);
  check(op.directories==3,"new directory names and file parent each forced once");
  check(op.events.stream().filter(e->e.startsWith("directory:")).toList().equals(List.of("directory:"+nested,"directory:"+d.resolve("one"),"directory:"+d)),"directories must force child before parent");noTemps(d);
 }
 public static void main(String[] args)throws Exception {
  successfulBatch();preparationFailures();publicationFailures();directoryFailure();backupFallback();cleanupAndLinkErrors();uncheckedCleanupFailures();newDirectories();publicationException();publicationCleanupException();retryDirectoryDurability();
  System.out.println("PASS: batch force ordering/counts, backups, writer/file-force/atomic-move/directory-force failures, hardlink fallback, directory creation, temporary cleanup");
 }
}
