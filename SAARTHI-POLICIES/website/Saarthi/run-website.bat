@echo off
REM Run Saarthi backend (requires build: mvn -q -DskipTests package)
set JAVA_EXE=%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21.0.8.9-hotspot\bin\java.exe
if not exist "%JAVA_EXE%" set JAVA_EXE=java
cd /d "%~dp0"
"%JAVA_EXE%" -jar target\saarthi-backend-1.0.0.jar
