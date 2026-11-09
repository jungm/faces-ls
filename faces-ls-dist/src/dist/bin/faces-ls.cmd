@echo off
rem Starts the Faces language server on stdin/stdout. Needs Java 21 or newer: JAVA_HOME, or java on PATH.
setlocal
set "HOME_DIR=%~dp0.."
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java") else (set "JAVA=java")
"%JAVA%" -XX:+UseSerialGC -Xss2m %FACES_LS_JAVA_OPTS% -jar "%HOME_DIR%\lib\faces-ls.jar" %*
