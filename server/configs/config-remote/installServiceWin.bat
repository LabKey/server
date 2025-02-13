if "%LABKEY_ROOT%" == "" goto noLabKeyRoot
if "%JAVA_HOME%" == "" goto noJavaHome

prunsrv.exe //IS//LabKeyRemoteServer --StdOutput="%LABKEY_ROOT%\logs\output.log" --StdError="%LABKEY_ROOT%\logs\output.log" --LogPath="%LABKEY_ROOT%\logs" --StartMode jvm --JavaHome "%JAVA_HOME%" --Jvm "%JAVA_HOME%\bin\server\jvm.dll" --JvmMx 1024 --Description "Allows this server to run pipeline jobs remotely for a LabKey Server instance" --DisplayName "LabKey Remote Pipeline Server" --Startup auto --Classpath "%LABKEY_ROOT%\tomcat-lib\labkeyBootstrap.jar" --StartClass org.labkey.bootstrap.RemoteServerBootstrap --StartParams="-modulesdir=%LABKEY_ROOT%\modules;-configdir=%LABKEY_ROOT%\config;-webappdir=%LABKEY_ROOT%\labkeywebapp" ++JvmOptions=--add-opens=java.base/java.lang=ALL-UNNAMED ++JvmOptions=--add-opens=java.base/java.io=ALL-UNNAMED ++JvmOptions=--add-opens=java.base/java.util=ALL-UNNAMED ++JvmOptions=--add-opens=java.desktop/java.awt.font=ALL-UNNAMED ++JvmOptions=--add-opens=java.base/java.text=ALL-UNNAMED
goto end

:noLabKeyRoot
echo The LABKEY_ROOT environment variable is not defined correctly.
goto end

:noJavaHome
echo The JAVA_HOME environment variable is not defined correctly.
goto end

:end