@echo off
setlocal
REM ============================================================
REM  Ket noi adb toi dien thoai POCO F3 qua Tailscale
REM  Chay tren PC OFFICE (may dev) - ke ca khi remote tu nha.
REM ============================================================

set "ADB=C:\Users\vu.lethanh\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "TS=C:\Program Files\Tailscale\tailscale.exe"
REM IP Tailscale co dinh cua dien thoai (poco-f3):
set "PHONE_IP=100.119.206.31"

echo [1/3] Kiem tra Tailscale toi dien thoai...
"%TS%" ping -c 2 --until-direct=false %PHONE_IP%
if errorlevel 1 (
  echo.
  echo [!] Khong ping duoc dien thoai qua Tailscale.
  echo     -^> Mo app Tailscale tren dien thoai, TAT roi BAT lai nut Connect
  echo        ^(xoa trang thai ket noi bi ket^), roi chay lai file nay.
  echo.
)

echo [2/3] adb connect %PHONE_IP%:5555 ...
"%ADB%" connect %PHONE_IP%:5555

echo [3/3] Danh sach thiet bi:
"%ADB%" devices -l
echo.
echo Neu thay "%PHONE_IP%:5555   device" -^> OK.
echo Mo Android Studio, chon thiet bi do de Run/Debug/Install.
echo Xem log: "%ADB%" -s %PHONE_IP%:5555 logcat -s ShopeeKit:*
echo.
pause
