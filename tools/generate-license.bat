@echo off
REM WinZenith License Key Generator
REM Usage: generate-license.bat 2027-06-14

set JAVA="C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe"
set JAVAC="C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\javac.exe"
set SCRIPT_DIR=%~dp0

if not exist "%SCRIPT_DIR%LicenseGenerator.class" (
    %JAVAC% "%SCRIPT_DIR%LicenseGenerator.java"
)

if "%~1"=="" (
    %JAVA% -cp "%SCRIPT_DIR%" LicenseGenerator
) else (
    %JAVA% -cp "%SCRIPT_DIR%" LicenseGenerator %1
)
