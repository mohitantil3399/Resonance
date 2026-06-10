@echo off
echo ==================================================
echo .NET SDK Portable Uninstaller
echo ==================================================
echo This will permanently delete the .NET SDK from your D drive.
echo Target folder: d:\SyncDevice\dotnet_sdk
echo.
pause

echo Deleting dotnet_sdk folder...
rmdir /s /q "d:\SyncDevice\dotnet_sdk"
echo Deleting install script...
del /q "d:\SyncDevice\dotnet-install.ps1"

echo.
echo .NET SDK has been successfully removed.
echo ==================================================
pause
