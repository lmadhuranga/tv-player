@ECHO OFF
SETLOCAL
IF EXIST "%~dp0gradle\wrapper\gradle-wrapper.jar" (
  java -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
  EXIT /B %ERRORLEVEL%
)
WHERE gradle >NUL 2>NUL
IF %ERRORLEVEL% EQU 0 (
  gradle -p "%~dp0" %*
  EXIT /B %ERRORLEVEL%
)
ECHO Gradle 8.13 not installed. Install Gradle 8.13 or open the project with Android Studio.
EXIT /B 1
