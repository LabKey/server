if "%LABKEY_ROOT%" == "" goto noLabKeyRoot
if "%JAVA_HOME%" == "" goto noJavaHome

prunsrv64.exe //IS//LabKeyRemoteServer --StdOutput="%LABKEY_ROOT%\logs\output.log" --StdError="%LABKEY_ROOT%\logs\output.log" --LogPath="%LABKEY_ROOT%\logs" --StartMode jvm --JavaHome "%JAVA_HOME%" --JvmMx 384 --Description "Allows this server to run pipeline jobs remotely for a LabKey Server instance" --DisplayName "LabKey Remote Pipeline Server" --Startup auto --Classpath "%LABKEY_ROOT%\labkeyBootstrap.jar" --StartClass org.labkey.bootstrap.RemoteServerBootstrap --StartParams="-modulesdir=%LABKEY_ROOT%\modules;-configdir=%LABKEY_ROOT%\config;-webappdir=%LABKEY_ROOT%\labkeywebapp"
goto end

:noLabKeyRoot
echo The LABKEY_ROOT environment variable is not defined correctly.
goto end

:noJavaHome
echo The JAVA_HOME environment variable is not defined correctly.
goto end

:end