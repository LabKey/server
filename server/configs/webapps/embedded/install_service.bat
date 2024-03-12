set LABKEY_HOME=C:\labkey\labkey
set LABKEY_APPS=C:\labkey\apps
set JAVA_HOME=%LABKEY_HOME%\java\jdk-17.0.9+9

prunsrv.exe //IS//labkeyServer ^
    --DisplayName "LabKey Server - labkeyServer" ^
    --Description "LabKey Server Scientific Data Management System" ^
    --Install "%LABKEY_APPS%\prunsrv.exe" ^
    --LogPath "%LABKEY_HOME%\logs" ^
    --StdOutput auto ^
    --StdError auto ^
    --Classpath "%LABKEY_HOME%\labkeyServer.jar" ^
    --Jvm "%JAVA_HOME%\bin\server\jvm.dll" ^
    --StartMode jvm ^
    --StopMode jvm ^
    --StartPath "%LABKEY_HOME%" ^
    --StopPath "%LABKEY_HOME%" ^
    --StartParams start ^
    --StartClass "org.springframework.boot.loader.launch.JarLauncher" ^
    --StopParams stop ^
    --StopMethod stop ^
    --StopClass "java.lang.System" ^
    --StopTimeout 60 ^
    --Startup manual ^
    --LogLevel Debug ^
    --JvmOptions "-Djava.io.tmpdir=%LABKEY_HOME%\labkey-tmp;-XX:+HeapDumpOnOutOfMemoryError;-XX:HeapDumpPath=%LABKEY_HOME%\labkey-tmp;-DterminateOnStartupFailure=true;%JvmArgs%" ^
    --JvmOptions9 "--add-opens=java.base/java.lang=ALL-UNNAMED#--add-opens=java.base/java.io=ALL-UNNAMED#--add-opens=java.base/java.util=ALL-UNNAMED#--add-opens=java.base/java.util.concurrent=ALL-UNNAMED#--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED" ^
    --JvmMs 2048 ^
    --JvmMx 2048