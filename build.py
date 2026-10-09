#!/usr/bin/env python3
"""Compile against this server's exact runtime; never modify its installed mods."""
import pathlib,subprocess,zipfile,shutil
root=pathlib.Path(__file__).resolve().parent
container='minecraft-crafty';target='/tmp/local-async-player-build'
subprocess.run(['docker','exec',container,'mkdir','-p',target],check=True)
subprocess.run(['docker','exec',container,'rm','-rf',target+'/src',target+'/classes'],check=True)
subprocess.run(['docker','cp',str(root/'src'),container+':'+target+'/'],check=True)
server='/crafty/servers/48577ca8-7812-4be5-8cc6-10f9e403d205'
# javac is provided by the container JDK; tests run on the actual Java 21 runtime.
script=f'''find {server}/libraries -name '*.jar' -print > {target}/jars
CP=$(paste -sd: {target}/jars)
find {target}/src/main/java {target}/src/test/java -name '*.java' > {target}/sources
java -m jdk.compiler/com.sun.tools.javac.Main -source 21 -target 21 -Xlint:-options -proc:none -cp "$CP" -d {target}/classes @{target}/sources
'''
subprocess.run(['docker','exec',container,'sh','-ec',script],check=True)
for test in ['SaveQueueTest','AtomicSaveTest','BackupSaveTest']:
 subprocess.run(['docker','exec',container,'/usr/lib/jvm/java-21-openjdk-amd64/bin/java','-cp',target+'/classes','local.asyncplayersave.'+test],check=True)
out=root/'build';out.mkdir(exist_ok=True)
shutil.rmtree(out/'classes',ignore_errors=True)
subprocess.run(['docker','cp',container+':'+target+'/classes',str(out)],check=True)
jar=out/'local-async-player-save-1.21.1-1.1.0.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for p in (out/'classes').rglob('*.class'):
  if 'Test' not in p.name:z.write(p,p.relative_to(out/'classes'))
 for p in (root/'src/main/resources').rglob('*'):
  if p.is_file():z.write(p,p.relative_to(root/'src/main/resources'))
print(jar)
