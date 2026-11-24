# KenshixDeck mimarisi ve doğrulama

## Windows

Rust host loopback üzerinde WSS dinler. İsteğe bağlı ikinci dinleyici tek bir özel IPv4 adresiyle sınırlıdır. Yerel sertifika ve OBS parolası DPAPI ile korunur. Host SQLite içinde yalnız oturum token'ının özeti ve son geçerlilik süresini saklar. Tepsiden veya CLI'dan iptal, bir sonraki istekten itibaren token'ı geçersiz kılar. Yeniden eşleştirme daha önceki oturumu değiştirir.

SQLite paneli paylaşılan tek revizyonla saklar. Revizyon uyuşmazlığı isteği reddeder ve güncel paneli döndürür. Eylem kimliği SQLite'da işlem öncesi yazılır. Bağlantı veya host kesilirse alınamayan sonuç belirsiz olarak gösterilir. İşlem kaydı 24 saattir. Bu zaman penceresi içinde aynı kimlik yeniden yürütülmez.

Core Audio varsayılan çıkış ve mikrofon seviyelerini okur. Uygulama oturumlarını yürütülebilir dosya yolundan gruplayarak ayarlar. Yol okunamazsa oturum kimliği ve PID açıklaması kullanır. Mikser, PC değişiklikleri için periyodik sorgu yapar. OBS bilgisi yalnız yerel OBS WebSocket 5 üzerinden alınır.

## Android

Android tarafında Views ve OkHttp kullanılır. Eşleştirmeden önce kullanıcı PC sertifikasının SHA-256 parmak izini kod içinde aktarır. İki taşıma için sertifikanın tamamı karşılaştırılır. Token Android Keystore anahtarıyla AES-GCM kullanılarak saklanır. Oturum kurulamazsa istemci önce USB'yi, sonra kayıtlı özel IPv4 adresini dener. Wi-Fi üzerindeyken USB düzenli olarak yoklanır. Bağlantı değiştiğinde bekleyen komutlar tekrar sıraya alınmaz.

## WSS v1

Uç nokta WSS /v1 ve JSON mesaj sınırı 64 KiB. İlk mesaj `pair` veya `hello` ile protokol sürümü ve kod ya da token içerir. `snapshot` paneli okur, `save` tam paneli ve revizyonunu taşır, `execute` kayıtlı buton UUID'si ve işlem UUID'si taşır. `mixer` ses durumunu döndürür. `set_volume` ve `set_mute` bir ses hedefi ve işlem UUID'si içerir. `obs_status` kaydı, yayını, Replay Buffer'ı ve aktif sahneyi okur. Sonuç ve hata mesajları ayırt edilir.

## Test

Yerel Windows smoke testi için Python ve websockets 15.0.1 gerekir. `scripts/smoke-host.py` geçici bir test veritabanı kullanır. Ses seviyesini değiştirmeden gerçek Core Audio listesini okur. Android lint ve JVM testleri Gradle wrapper üzerinden çalışır.

Cihaza kurulum, Wi-Fi geçiş süresi, OBS gerçek komutları ve donanım performansı fiziksel test gerektirir. Grafiksel Windows editörü, profiller ve klasörler, kapsamlı makro ve otomasyon, donanım sensörleri, Device Owner kiosk ve cihazlara otomatik imzalı güncelleme bu geliştirme derlemesinin açık iş kalemleridir. OBS sahne ve kaynak düzenleme, mikser cihaz değiştirme ve uygulama ikonları da açık kalemlerdir.
