@ECHO OFF

if not "%2" == "" goto :usage
if "%1" == "-h" goto :usage
if "%1" == "-help" goto :usage
if "%1" == "--help" goto :usage
if "%1" == "h" goto :usage
if "%1" == "help" goto :usage

set HOST=""
set PORT=""
set MODPATH=""

set BUILD=%1
if not "%BUILD%" == "" goto :buildinfo

:buildsinfo
set CMD=java -cp lib/jenkins-client.jar org.beeherd.jenkins.client.MetadataApp -h %HOST% -p %PORT% -mp %MODPATH% -sb
goto :end

:buildinfo
set CMD=java -cp lib/jenkins-client.jar org.beeherd.jenkins.client.MetadataApp -h %HOST% -p %PORT% -mp %MODPATH% -b %BUILD%

:end

%CMD%

set BUILD=
set MODPATH=
set CMD=
set HOST=
set PORT=
set BUILD=

goto :exit

:usage
echo List Jenkins builds and retrieve metadata about individual builds.
echo USAGE:  %0 

:exit
