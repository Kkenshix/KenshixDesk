# KenshixDeck

Windows 11 için Rust sistem tepsisi hostu ve Galaxy M22 için Türkçe, siyah temalı native Android kontrol paneli. Kullanıcının gönderdiği ejderha görseli Android uygulama ikonunda, üst çubukta ve Windows sistem tepsisinde kullanılır.

Bu sürüm bir geliştirme derlemesidir. CI, Windows x64 EXE ve Android debug APK üretir. APK ilk deneme içindir ve ileride farklı bir imza anahtarı ile imzalanan üretim APK'sına yerinde yükseltme garantisi vermez.

## Hızlı deneme

1. Windows EXE'yi PowerShell'den `kenshixdeck.exe --pair` ile çalıştır. Host port 43851'i varsayılan olarak yalnız loopback üzerinde dinler. Konsol beş dakika geçerli bir kod gösterir. Tepsi menüsü yeni bir kod oluşturup Windows panosuna kopyalayabilir.
2. Galaxy M22'de USB hata ayıklamayı etkinleştir ve bu PC için ilk Android güvenlik onayını telefondan ver. `adb devices -l` çıktısında M22 `device` olmalı.
3. Test APK'sının sertifikasını Android SDK `apksigner verify --print-certs` ile kontrol et. Beklenen test sertifika parmak iziyle birlikte `scripts/setup-phone.ps1` aracına APK, apksigner ve ADB yollarını ver. Debug APK için -TestOnly seçeneğini kullan. Script cihaz kimliğini tespit eder, imzayı doğrular, APK'yı yükler, USB reverse yönlendirmesini kontrol eder ve uygulamayı açar.
4. Telefonun uygulamasında **PC ile eşleştir** ekranına kodu yaz. Hostu `--adb ADB_YOLU M22_SERI` ile çalıştırırsan yeniden bağlantıda USB reverse otomatik yenilenir.
5. Boş panelden **+** ile bir buton oluştur. **Ses Mikseri** butonu genel ses seviyesini, varsayılan mikrofonu ve gerçek uygulama oturumlarını ayrı bir ekranda açar. Düzenleme menüsü butonları değiştirme veya silme işlevi sunar.

PC tarafında `kenshixdeck.exe --buttons`, `kenshixdeck.exe --add-button "Ses" mixer` ve `kenshixdeck.exe --remove-button BUTON_KIMLIGI` aynı veritabanında çalışır. Android bir sonraki durum sorgusunda değişikliği görür.

`scripts/start-windows.ps1` hostu gizli başlatabilir. `-StartAtLogon` geçerli Windows kullanıcısının Startup klasöründe kısayol oluşturur. İsteğe bağlı `-Lan` bir Windows Özel ağ arabirimine atanmış yerel IPv4 ister. Güvenlik duvarında gerekirse yalnız bu uygulama, yerel adres, TCP 43851, Özel profil ve yerel alt ağ için erişim tanımlanır. Android eşleştirme ekranında aynı IPv4 girilir. Windows ağı Genel profildeyse USB modunu kullan.

OBS WebSocket 5 etkinleştirildikten sonra `kenshixdeck.exe --configure-obs 4455` parola girişini açar. Parola DPAPI ile saklanır. Android'de kayıt, yayın, Replay Buffer eylemleri ve OBS durum ekranı eklenebilir. Bir isteğin başarılı olması OBS'nin o isteği kabul ettiği anlamına gelir. Kayıt ve yayın çıktısının güncel durumu OBS ekranından okunur.

**Panel düzeni** menüsünden profil, sayfa ve iç içe klasör oluşturulabilir. Butonun genişliği ve rengi düzenleyicide seçilir. **PC Performansı** ekranı CPU kullanımı, RAM ve sistem diski ölçümlerini Windows'tan okur. Tam ekran önizlemesi ve Android Lock Task kurulumu [docs/kiosk.md](docs/kiosk.md) dosyasındadır.

## Derleme

Windows için Rust 1.97.1, MSVC x64 araçları ve Windows SDK gerekir. Android için JDK 21, Android SDK 35 ve Build Tools 35.0.0 gerekir.

```powershell
cargo fmt --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
cargo build --release --locked
Set-Location android
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

Android üretim imzalama yapılandırması `DECK_KEYSTORE`, `DECK_STORE_PASSWORD`, `DECK_KEY_ALIAS` ve `DECK_KEY_PASSWORD` ortam değişkenleriyle etkinleşir. APK yüklemek için aynı ürün anahtarı korunmalıdır. CI şu anda debug APK üretir.

Mimari ve doğrulama kapsamı [docs/architecture.md](docs/architecture.md) dosyasındadır.
