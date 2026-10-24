@echo off
rem ============================================================
rem  AI Teaching Knowledge Base - launcher
rem  NOTE: this file is intentionally pure ASCII. cmd.exe parses
rem  .cmd files using the OEM codepage (GBK here), so UTF-8
rem  Chinese text in this file would be mis-parsed as commands.
rem  Chinese output is produced by the JVM at runtime instead.
rem
rem  Usage: run.cmd                        (default port 8080)
rem         run.cmd --server.port=9090     (any Spring Boot arg)
rem ============================================================
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
  if exist "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
  )
)

if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)

set "JAR=target\ai-teaching-kb-1.0.0.jar"
if not exist "%JAR%" (
  echo [ERROR] jar not found: %JAR%
  echo         build it first:
  echo         tools\apache-maven-3.9.9\bin\mvn.cmd -B -s tools\settings.xml package -DskipTests
  exit /b 1
)

echo [INFO] java: %JAVA_EXE%
echo [INFO] jar : %JAR%
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" %*
endlocal
