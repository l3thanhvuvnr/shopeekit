@echo off
setlocal
REM ============================================================
REM  Re-arm wireless ADB tren dien thoai (cong TCP 5555)
REM  Chay khi dien thoai CAM USB vao may nay.
REM  Can lam:
REM    - 1 lan dau tien, VA
REM    - lai sau MOI lan dien thoai REBOOT (reboot xoa che do tcpip).
REM  May nay can co platform-tools (adb). PC office da co san.
REM  PC ca nhan o nha: tai "SDK Platform Tools" cua Google neu chua co.
REM ============================================================

set "ADB=C:\Users\vu.lethanh\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

echo Thiet bi USB dang ket noi:
"%ADB%" devices
echo.
echo Arming adbd len TCP 5555 (tren moi interface, gom ca Tailscale)...
"%ADB%" -d tcpip 5555
echo.
echo Xong. Gio tu PC office chay connect-phone.bat de ket noi qua tailnet.
echo (USB van dung binh thuong; cam/rut deu duoc.)
echo.
pause
