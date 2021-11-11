@echo off

if "%GRAALVM_HOME%"=="" (
    echo Please set GRAALVM_HOME
    exit /b
)

set JAVA_HOME=%GRAALVM_HOME%
set PATH=%GRAALVM_HOME%\bin;%PATH%

set /P VERSION=< resources\POD_BABASHKA_AWS_VERSION
echo Building version %VERSION%

if "%GRAALVM_HOME%"=="" (
echo Please set GRAALVM_HOME
exit /b
)

bb clojure -J-Dclojure.main.report=stderr -T:build uber

call %GRAALVM_HOME%\bin\gu.cmd install native-image

call %GRAALVM_HOME%\bin\native-image.cmd ^
  "-cp" "pod-babashka-aws.jar" ^
  "-H:Name=pod-babashka-aws" ^
  "-H:+ReportExceptionStackTraces" ^
  "-H:EnableURLProtocols=jar" ^
  "--report-unsupported-elements-at-runtime" ^
  "--initialize-at-build-time=org.eclipse.jetty" ^
  "--initialize-at-build-time=com.cognitect.transit" ^
  "-H:EnableURLProtocols=http,https,jar" ^
  "--enable-all-security-services" ^
  "-H:ReflectionConfigurationFiles=reflection.json" ^
  "-H:ResourceConfigurationFiles=resources.json" ^
  "--verbose" ^
  "--no-fallback" ^
  "--no-server" ^
  "-J-Xmx3g" ^
  "pod.babashka.aws"

if %errorlevel% neq 0 exit /b %errorlevel%

echo Creating zip archive
jar -cMf pod-babashka-aws-%VERSION%-windows-amd64.zip pod-babashka-aws.exe
