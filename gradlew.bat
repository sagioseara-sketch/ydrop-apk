@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
@rem Set local scope
setlocal
set DIRNAME=%~dp0
java -jar "%DIRNAME%\gradle\wrapper\gradle-wrapper.jar" %*
endlocal
