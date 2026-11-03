# v7.9: bỏ keep toàn bộ thư viện (v7.8 keep coil.** + media3.** khiến R8 không thu gọn được).
# Media3 và Coil đều đóng gói sẵn consumer rules trong AAR; app parse JSON thủ công bằng
# org.json (không reflection theo model) nên chỉ cần giữ các điểm vào dưới đây.

# Coil: giữ factory của ImageLoader (nạp qua ServiceLoader/reflection)
-keep class coil.ImageLoaderFactory { *; }
-keep class com.kenhlive.tv.KenhLiveApp { *; }
-dontwarn coil.**

# Media3: giữ factory renderer/datasource mặc định (nạp thử bằng reflection, lỗi sẽ tự bỏ qua)
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-keep class androidx.media3.exoplayer.DefaultLoadControl { *; }
-keep class androidx.media3.datasource.DefaultDataSource$Factory { *; }
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory { *; }
-dontwarn androidx.media3.**
