# AirPlayRx - nhận video AirPlay trên Android 9+ / Samsung DeX
1. Mở thư mục bằng Android Studio (Koala+), chờ Gradle sync, Build > Build APK.
2. Cài APK, mở app, cho phép Thông báo. Vào Cài đặt > Ứng dụng > AirPlay Receiver:
   bật "Hiển thị trên các ứng dụng khác" (để video mới tự bật app) và tắt tối ưu pin.
3. iPhone và Android phải cùng mạng Wi-Fi/LAN. Phát video (Safari, YouTube...) > biểu tượng AirPlay > chọn "AirPlay <tên máy>".
Tuỳ chỉnh: AirPlayServer.IGNORE_REMOTE_STOP, AirPlayServer.FEATURES.
