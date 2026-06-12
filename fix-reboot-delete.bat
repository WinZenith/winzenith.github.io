@echo off
echo This will remove only "explorer.exe" from the reboot-delete list.
echo Other pending file renames will be preserved.
echo.
echo Please run this as Administrator (right-click -^> Run as administrator).
echo.
pause
powershell -NoProfile -ExecutionPolicy Bypass -Command "$path='HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager'; $name='PendingFileRenameOperations'; $val=Get-ItemPropertyValue -Path $path -Name $name -ErrorAction SilentlyContinue; if(-not $val){Write-Host 'No pending operations found.'; pause; exit}; $before=$val.Count; $val=$val | Where-Object {$_ -notlike '*explorer.exe*'}; if($val.Count -eq 0){Remove-ItemProperty -Path $path -Name $name -Force}else{Set-ItemProperty -Path $path -Name $name -Value $val -Type MultiString}; Write-Host \"Removed explorer.exe. Entries: $before -> $($val.Count)\"; pause"
