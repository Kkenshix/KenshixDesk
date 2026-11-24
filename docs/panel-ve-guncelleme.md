# Panel, makro ve güncelleme

M22 sabit ters yatay yönde açılır. Ana panel ve profiller arasında ekranın iç kısmından yatay kaydır. Bir profilin sayfaları arasında da aynı hareket çalışır. M22 kurulumunda `configure-kiosk.ps1 -SamsungGestures` seçeneği, Samsung'un işaretsiz hareket modunu seçer ve soldaki gezinme düğmesi şeridini kaldırır. Ayarlar → Profiller ve paneller menüsünden bir panele doğrudan gidebilir, profil, sayfa ve klasör oluşturabilirsin.

Windows tepsisinden **Paneli düzenle** seçeneğini aç. Soldaki alan telefonun yerleşimini gösterir. Butonun ortasından başka butona sürükleyerek sırayı, sağ kenarından çekerek bir veya iki hücrelik genişliği değiştir. Sağ ayarlardan ad, eylem, panel, renk ve GIF/PNG/JPEG seçilebilir. Görsel en fazla 512 KB ve 800 × 800 olabilir. **Uygula** ile düzenleme kaydedilir. Yerleşim sürüklemeleri bırakınca kaydedilir. Telefon başka bir değişiklik kaydetmişse düzenleyici güncel sürümü yükler. **M22 ekranını göster · USB** seçeneği, KenshixDeck telefonda öndeyken M22'nin gerçek görüntüsünü aynı pencereye getirir.

Tek tuş veya kombinasyon için eylem olarak **Tuş, tıklama veya makro** seç. **Makro adımı** menüsünden tuş, yazı, Windows koordinatına tıklama, uygulama açma, bekleme, OBS hazır olma ve OBS koşulu seçilebilir. Tuşlar için örnek: `Ctrl+Shift+M`. Basılı tutma süresi sıfırsa tek basış yapılır. Bir makro çalışırken butona yeniden dokunarak iptal isteği gönder. Otomasyon varsayılan olarak elle çalışır. Gerekirse belirli saat, host açılışı, uygulama açılışı/kapanışı veya OBS kayıt olayını seç. Disk doluluk tetikleyicisi uygulama içi uyarı gösterir. Olay tetikleyicileri ilk gözlemden sonra durum değişikliği bekler.

Ekran açıkken 30 saniye sonra parlaklık azalır. Beş dakika sonra saat, tarih ve erişilebilen PC CPU/GPU/RAM değerleri gösterilir. İlk dokunuş paneli uyandırır. Fiziksel güç tuşuyla kapatılan Android ekranı, panelin ekran içeriği göstermesine izin vermez.

## Stable yayın kurulumu

Geliştirme APK'sı yerel debug sertifikasıyla imzalıdır. Kurulu uygulama aynı Android imzasını gerektirir. İlk üretim geçişi için aynı Android imzalayıcı sürekliliğini sağla veya debug kurulumundan üretim kurulumuna ayrı cihaz hazırlığı planla.

GitHub `stable` environment altında `DECK_KEYSTORE_BASE64`, `DECK_STORE_PASSWORD`, `DECK_KEY_ALIAS`, `DECK_KEY_PASSWORD` ve `DECK_RELEASE_ED25519_PKCS8_BASE64` sırlarını tanımla. `DECK_UPDATE_PUBLIC_KEY` repo değişkeni Ed25519 ham 32 bayt açık anahtarın Base64 karşılığıdır. `DECK_PUBLISH_STABLE=true` ana dalda başarılı CI sonrasında imzalı stable işini etkinleştirir. Özel anahtarları repoya koyma. Workflow APK imzasını ve manifest imzasını doğruladıktan sonra release oluşturur.

Kurulu Windows host, gömülü açık anahtarla manifest imzası, protokol ve build sırasını, ardından dosya uzunluğu ve SHA-256 değerlerini doğrular. Android Package Manager önceki APK imzasının devamlılığını doğrular. Host yalnız seçilmiş USB seri numarasını kullanır. USB cihazı yoksa Windows kurulumu ilerler ve APK USB geri gelince kontrol edilir. Sürüm ve son kontrol bilgileri tepside **Bağlantı ve sürüm**, telefonda **Sürüm** altında görünür.
