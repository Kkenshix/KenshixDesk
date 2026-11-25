# Panel, makro ve güncelleme

M22 sabit ters yatay yönde açılır. Ana panel ve profiller arasında ekranın iç kısmından yatay kaydır. Bir profilin sayfaları arasında da aynı hareket çalışır. Sağ kaydırma sonraki, sol kaydırma önceki öğeye geçer. Seçili profil üst çubukta aydınlanır ve yeni sayfanın adı geçiş sırasında kısa süre görünür. Telefon son açık paneli ve her profilin son konumunu hatırlar. M22 kurulumunda `configure-kiosk.ps1 -SamsungGestures` seçeneği, Samsung'un işaretsiz hareket modunu seçer ve soldaki gezinme düğmesi şeridini kaldırır. Ayarlar → Profiller ve paneller menüsünden bir panele doğrudan gidebilir, profil, sayfa ve klasör oluşturabilirsin.

Windows tepsisinden **Paneli düzenle** seçeneğini aç. Soldaki alan telefonun yerleşimini gösterir. Butonun ortasından başka butona sürükleyerek sırayı, sağ alt köşesinden çekerek enini ve boyunu değiştir. Yan ayarlarda iki ölçüyü piksel olarak yazabilirsin. Sağ ayarlardan ad, eylem, panel, renk ve GIF, PNG, JPG veya uyumlu MP4 seçilebilir. Dosya en fazla 8 MB olabilir. Büyük fotoğraflar en fazla 800 × 800 ölçüsüne küçültülür. **Kaydet** ile değişiklikler açık M22 paneline gönderilir. Medya, butonun içinde en-boy oranı korunarak ve gerekirse küçültülerek gösterilir. MP4 butonlarda sessiz döngüde oynar ve Windows'taki USB ekran görünümünde izlenebilir. Telefon başka bir değişiklik kaydetmişse düzenleyici güncel sürümü yükler. **M22 gerçek ekranı** sekmesi, KenshixDeck telefonda öndeyken M22'nin gerçek görüntüsünü aynı pencereye getirir. Görünümü pencereye sığdırabilir veya 1:1 piksel oranında açabilirsin.

Yeni buton eklemek için üst çubuktaki **+** işaretine dokun. Tek tuş veya kombinasyon için eylem olarak **Tuş, tıklama veya makro** seç. **Makro adımı** menüsünden tuş, yazı, Windows koordinatına tıklama, uygulama açma, bekleme, OBS hazır olma ve OBS koşulu seçilebilir. Tuşlar için örnek: `Ctrl+Shift+M`. Koordinat tıklamalarında Windows editörü üç saniye sonra gerçek imleç konumunu alabilir. Basılı tutma süresi sıfırsa tek basış yapılır. Düzenlemek için butona basılı tut. Makro çalışırken aynı butona basılı tutup **Makroyu iptal et** seçeneğini kullan. Çift dokunuş yalnızca atanmış bir eylemi varsa o eylemi çalıştırır. Otomasyon varsayılan olarak elle çalışır. Gerekirse belirli saat, host açılışı, uygulama açılışı/kapanışı veya OBS kayıt olayını seç. Disk doluluk tetikleyicisi uygulama içi uyarı gösterir. Olay tetikleyicileri ilk gözlemden sonra durum değişikliği bekler.

Ekran 15 saniye sonra kararır ve 45 saniye sonra saat, tarih ve PC CPU/GPU/RAM değerlerini gösterir. Üst çubuktaki saate dokunarak bu görünümü hemen aç. İlk dokunuş paneli uyandırır. Yönetilen kiosk ekrandayken yan tuşun uyku olayından sonra saat görünümü Android ekranı uyandırmayı dener. Saat görünümü açıkken yan tuş normal biçimde ekranı kapatır.

## Medya ve Windows denetimi

Saat ekranındaki oynatıcı kartı Windows'un bildirdiği parça ve uygulamayı gösterir. Önceki, oynat/duraklat, sonraki ve ses kontrolleri saat görünümünü açık tutar. Bu kontrollerin dışına dokunarak panele dön. Ses kaynağı belirlenebildiğinde uygulamanın sesi, diğer durumlarda kartta açıkça belirtilen Windows genel sesi ayarlanır.

Üst çubuktaki **Video** her profilden açılır. Windows'un medya bilgisi yayımladığı video ve tarayıcı oturumlarını gösterir. Tarayıcı müzikle videoyu aynı oturum türüyle bildirebildiği için bu listede tarayıcıdan çalan müzik de görünebilir. Pencere kumandası, klavye ve touchpad ile bağlantı tanılama **Ayarlar** içindedir. Windows öne alma izni vermezse pencere görev çubuğunda işaretlenebilir. Mikserde uygulama adını basılı tutarak sabitle, **Ses profilleri** ile çalışan uygulamaların seslerini kaydet veya uygula.

Windows editöründe **Geri al**, **Yinele**, Ctrl ile çoklu seçim ve sürükleme kılavuzları bulunur. Makro testinin başında hedef pencereyi seçmek için üç saniye vardır. Panel düzenini kaydetmeden adım sonuçlarını okuyabilirsin. Buton ayarlarından çift dokunma, basılı tutma, paylaşılan makro ve onay eklenebilir.

## Spotify, yan mikser ve hızlı tuşlar

Ana panelden sağa kaydırınca **Ses Mikseri** sayfası açılır. Sola kaydırarak ana panele dönersin. Ses kaydırıcısını sürüklemek sayfa değiştirmez. Açık düzenleme menüleri boyunca ekran kararma ve saat zamanlayıcıları durur, menü kapanınca baştan saymaya başlar.

Ana panele eklenen yatay **Spotify** kartı oynatma kumandasını ve Spotify'ın Windows ses oturumunu açar. Spotify kendi ses oturumu var olduğu sürece pencere küçültülmüşken de ses ve sessize alma çalışır. Karıştırma, tekrar ve parça konumu uygulamanın Windows'a sunduğu ölçüde kullanılabilir. Spotify ses oturumu kapanmışsa bu kart ve saat ekranındaki Spotify kaydırıcısı Windows genel sesine geçmez.

Bu panelde **OBS Kaydı** F9 gönderir. OBS kullanırken kaydı başlatmak ve durdurmak için F9 kısayolunu OBS’nin kendi tuş atamalarında kullan. Discord kısayolları kullanılıyorsa masaüstü Discord'da **Tuş Atamaları** bölümünde sağırlaştırma için Page Down, mikrofon için Page Up eşlenir.

## Opera’dan M22’ye video devri

Windows uygulamasını bir kez `--setup-video-browser` ile çalıştır. Çıktıdaki klasörü Opera’da **Uzantılar → Geliştirici modu → Paketlenmemiş uzantıyı yükle** üzerinden seç. İlk kurulumdan sonra KenshixDeck Windows uygulamasını yeniden başlat ve açık YouTube veya Kick sekmesini bir kez yenile.

Opera’da oynayan son desteklenen sekmeyi arka plana attığında video M22’de açılır. **Kapat** panele döndürür. Alt çubuktaki **Ses** telefonun medya sesini ayarlar. YouTube kaldığı saniyeden başlar ve telefonda oynatma doğrulanınca Opera’daki oynatıcı duraklatılır. Kick, kanalın canlı yayınını açar. Telefonun oynatmayı başlattığı Kick tarafından bildirilmediği için PC’deki Kick sayfası kendi durumunda kalır. YouTube’da gömülü oynatmaya kapalı videolar ve Kick arşivleri desteklenmez.

## Stable yayın kurulumu

Geliştirme APK'sı yerel debug sertifikasıyla imzalıdır. Kurulu uygulama aynı Android imzasını gerektirir. İlk üretim geçişi için aynı Android imzalayıcı sürekliliğini sağla veya debug kurulumundan üretim kurulumuna ayrı cihaz hazırlığı planla.

GitHub `stable` environment altında `DECK_KEYSTORE_BASE64`, `DECK_STORE_PASSWORD`, `DECK_KEY_ALIAS`, `DECK_KEY_PASSWORD` ve `DECK_RELEASE_ED25519_PKCS8_BASE64` sırlarını tanımla. `DECK_UPDATE_PUBLIC_KEY` repo değişkeni Ed25519 ham 32 bayt açık anahtarın Base64 karşılığıdır. `DECK_PUBLISH_STABLE=true` ana dalda başarılı CI sonrasında imzalı stable işini etkinleştirir. Özel anahtarları repoya koyma. Workflow APK imzasını ve manifest imzasını doğruladıktan sonra release oluşturur.

Kurulu Windows host, gömülü açık anahtarla manifest imzası, protokol ve build sırasını, ardından dosya uzunluğu ve SHA-256 değerlerini doğrular. Android Package Manager önceki APK imzasının devamlılığını doğrular. Host yalnız seçilmiş USB seri numarasını kullanır. USB cihazı yoksa Windows kurulumu ilerler ve APK USB geri gelince kontrol edilir. Sürüm ve son kontrol bilgileri tepside **Bağlantı ve sürüm**, telefonda **Sürüm** altında görünür.
