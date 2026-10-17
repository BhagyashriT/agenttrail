@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@SET __MVNW_PSMODULEP_=
@FOR /F "usebackq tokens=1* delims==" %%A IN ("%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties") DO @(
@IF "%%A"=="wrapperUrl" SET __MVNW_CMD__=%%B
)
@IF NOT "%__MVNW_CMD__%"=="" GOTO wrapperUrlSet
@ECHO Error: wrapperUrl is not defined in .mvn\wrapper\maven-wrapper.properties >&2
@EXIT /B 1
:wrapperUrlSet
@SET __MVNW_WRAPPER_JAR__=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar
@IF EXIST "%__MVNW_WRAPPER_JAR__%" GOTO runWrapper
@ECHO Downloading Maven Wrapper...
@powershell -ExecutionPolicy ByPass -File "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\MavenWrapperDownloader.ps1" "%__MVNW_CMD__%" "%__MVNW_WRAPPER_JAR__%"
@IF ERRORLEVEL 1 GOTO error
:runWrapper
@SET __MVNW_LAUNCHER__=org.apache.maven.wrapper.MavenWrapperMain
@SET MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR%
@"%JAVA_HOME%\bin\java.exe" %MAVEN_OPTS% -classpath "%__MVNW_WRAPPER_JAR__%" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" %__MVNW_LAUNCHER__% %*
@IF ERRORLEVEL 1 GOTO error
@GOTO end
:error
@SET ERROR_CODE=%ERRORLEVEL%
@ECHO Maven Wrapper failed with error code %ERROR_CODE% >&2
@EXIT /B %ERROR_CODE%
:end
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@SET __MVNW_PSMODULEP_=
