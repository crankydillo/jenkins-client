@ECHO OFF

if not "%2" == "" goto :usage
if "%1" == "-h" goto :usage
if "%1" == "-help" goto :usage
if "%1" == "--help" goto :usage
if "%1" == "h" goto :usage
if "%1" == "help" goto :usage

set MODPATH=""
set RELPATH=""
set HOST=""
set PORT=""
set BASEDIR="./builds"
set POSTJS=""
set PREJS=""

set BUILD=%1
if "%BUILD%" == "" goto :postjs
set BUILD=-bn %1

:postjs
if "%POSTJS%" == "" goto :prejs
set POSTJS=-postjs %POSTJS%

:prejs
if "%PREJS%" == "" goto :run
set PREJS=-prejs %PREJS%

:run
set CMD=java -cp lib/jenkins-client.jar org.beeherd.jenkins.client.RetrieveApp -s %HOST% -p %PORT% -mp %MODPATH% -rp %RELPATH% %BUILD% -e -b %BASEDIR% -d %PREJS% %POSTJS%

echo %CMD%
%CMD%

set HOST=
set PORT=
set MODPATH=
set RELPATH=
set BUILD=
set BASEDIR=
set POSTJS=
set PREJS=
set CMD=

goto :exit

:usage
echo Retrieve Jenkins builds. 
echo USAGE:  %0 [build-num]
echo where build-num defaults to last Jenkins build

:exit
