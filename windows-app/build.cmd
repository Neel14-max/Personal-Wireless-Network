@echo off
REM ============================================================
REM  Build TetherDirect.exe using the C# compiler that ships
REM  with Windows (.NET Framework). No Visual Studio needed.
REM ============================================================
setlocal

set "HERE=%~dp0"
set "CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if not exist "%CSC%" set "CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe"

if not exist "%CSC%" (
    echo Could not find the C# compiler ^(csc.exe^).
    echo Make sure .NET Framework 4.x is installed ^(it is on all Windows 10/11^).
    pause
    exit /b 1
)

echo Compiling TetherDirect.exe ...
"%CSC%" /nologo /target:winexe /platform:x64 ^
    /out:"%HERE%TetherDirect.exe" ^
    /win32manifest:"%HERE%app.manifest" ^
    /reference:System.Windows.Forms.dll ^
    /reference:System.Drawing.dll ^
    "%HERE%TetherDirect.cs"

if errorlevel 1 (
    echo.
    echo BUILD FAILED.
    pause
    exit /b 1
)

echo.
echo Build OK: %HERE%TetherDirect.exe
echo Make sure tun2socks-windows-amd64.exe and wintun.dll are in this folder.
echo.
pause
