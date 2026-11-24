# KenshixDeck

Windows host Rust, Android uygulaması native Kotlin. Ürün adı KenshixDeck, kaynak deposu Kkenshix/KenshixDesk. Uygulama ev ağı ve USB üzerinden çalışır. Üretim özellikleri için WAN servisi, telemetri, uzak masaüstü, genel dosya aktarımı, kullanıcı verisi yedekleme veya eklenti sistemi ekleme.

- Host kodu src/ altındadır. Ağ için server.rs, tipli yükler için protocol.rs, revizyon ve işlem kaydı için store.rs kullanılır. COM çağrıları audio.rs içinde aynı iş parçacığında yapılır.
- Android kaynakları android/app/src/main/java/com/kenshix/deck/ altındadır. Link.kt taşıma ve sertifika doğrulamasını, Identity.kt Keystore kimliğini, MainActivity.kt paneli yönetir.
- Windows'ta cargo fmt --check, cargo clippy --workspace --all-targets --locked -- -D warnings, cargo test --workspace --locked ve cargo build --release --locked çalıştır.
- Android Gradle wrapper ile assembleDebug, lintDebug ve testDebugUnitTest çalıştır. Gerçek cihaz sonucu ile derleme sonucunu ayrı kaydet.
- Graphify haritasını kod değişiklikleriyle güncelle. Çıktı graphify-out/ altında yereldir. Haritada id benzersizliği, ilişki uçları ve dosya hashlerini doğrula.
- İmzalama dosyalarını, eşleştirme kodlarını ve parolaları commit etme. CI geliştirme dosyası üretir. Gerçek cihaz kabul testleri ve kalıcı imzalama düzeni kurulmadan üretim yayını yapma.
