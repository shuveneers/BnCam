@echo off
setlocal
cd /d "%~dp0\.."
git apply --check "%~dp0DELTA_0178.patch" || goto :error
git apply "%~dp0DELTA_0178.patch" || goto :error
echo DELTA 0178 applied successfully.
exit /b 0
:error
echo DELTA 0178 was NOT applied. Verify that the project is still based on the expected source state.
exit /b 1
