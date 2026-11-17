@echo off
rem WDPC server launcher for Windows (нужен только Python 3)
where py >nul 2>nul && (py %~dp0wdpc_server.py & goto end)
where python >nul 2>nul && (python %~dp0wdpc_server.py & goto end)
echo Python 3 ne naiden. Ustanovite ego iz Microsoft Store (bepлатно) i zapustite etot fail snova.
:end
pause
