@echo off
setlocal
REM ============================================================
REM  Xem + dieu khien man hinh dien thoai qua Tailscale (scrcpy)
REM  Chay SAU khi da connect-phone.bat (dien thoai co trong adb devices).
REM  Luu y:
REM   - Dieu khien (cham/vuot/go phim) can: Developer options ->
REM     "USB debugging (Security settings)" ON tren MIUI/HyperOS.
REM   - Qua DERP relay (~150-400ms) video se co do tre; hop de QUAN SAT
REM     va dieu khien cham, khong hop thao tac nhanh.
REM ============================================================

set "PHONE_IP=100.119.206.31"

REM Thong so nhe cho duong relay (giam do phan giai/bitrate/fps cho muot):
scrcpy -s %PHONE_IP%:5555 --max-size 1024 --video-bit-rate 2M --max-fps 20 --no-audio --window-title "POCO F3 (remote qua Tailscale)"

if errorlevel 1 (
  echo.
  echo [!] scrcpy loi. Kiem tra:
  echo     - Da chay connect-phone.bat chua? ^(adb devices phai thay %PHONE_IP%:5555^)
  echo     - Neu 'scrcpy' khong nhan dien: mo lai cmd moi ^(winget vua them vao PATH^).
)
pause
