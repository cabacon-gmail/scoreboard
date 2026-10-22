@echo off
rem Starts CRG and the game recorder together (Windows: double-click).
rem Devices use http://<this laptop>:8000/ as usual. Put this file and crg-recorder.jar in the CRG folder.
rem Press Ctrl-C (or close the window) to stop; the recording is written then.
cd /d "%~dp0"
set JAVA=java
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set JAVA="%JAVA_HOME%\bin\java.exe"
%JAVA% -jar crg-recorder.jar --crg=lib\crg-scoreboard.jar %*
pause
