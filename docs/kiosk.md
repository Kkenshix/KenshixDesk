# Galaxy M22 kiosk kurulumu

KenshixDeck normal uygulama olarak açılır. **Ayarlar → Kiosk → Tam ekranı dene** menüsü görünümü gösterir. Android ana ekranı ve uygulama geçişine erişim varsa kip yalnızca önizlemedir.

Tam kiosk için Android'in Device Owner ve Lock Task özellikleri kullanılır. Galaxy M22'de başka bir yönetici, ek Android kullanıcısı, iş profili veya kayıtlı hesap olmamalı. Android kurulumu ve ilk USB hata ayıklama onayı telefondan tamamlanır. Telefona hesap eklemeden kurulumu tamamlamak yönetim adımını kolaylaştırır.

1. APK'yı yükle ve uygulamanın USB üzerinden hosta bağlandığını doğrula. Geliştirme APK'sı test-only olarak işaretlenir. Bu dosyayı kurarken setup-phone.ps1 için `-TestOnly` gerekir.
2. `scripts/configure-kiosk.ps1 -Adb ADB_YOLU -Serial M22_SERI` komutunu çalıştır. Betik model, kullanıcılar, hesaplar ve Android yöneticisini kontrol eder.
3. Telefonda **Ayarlar → Kiosk → Kiosku aç** seçeneğini kullan. Uygulama ana ekran olarak kaydedilir ve Lock Task başlar.
4. HOME ve Son Uygulamalar tuşlarıyla başka bir uygulamanın açılmadığını kontrol et. Sonra telefonu yeniden başlat ve Android açıldıktan sonra KenshixDeck'in HOME olarak çalıştığını doğrula.
5. **Ayarlar → Bakım moduna geç → Kiosk çıkışı** ile normal Android kullanımına dön. Kiosk çıkışı yerel onay ekranıyla korunur. İlave PIN istenmez.

Telefonun kendi ekran kilidi ve Android yönetim kontrolleri geçerli kalır. Bu kurulum sistem uygulamalarını kaldırmaz. Geliştirme APK'sı üzerinde Android yöneticisi daha sonra açıkça kaldırılmak istenirse önce bakım moduna geçilir, ardından test-only paketi için hedefli `adb shell dpm remove-active-admin com.kenshix.deck/.DeckAdminReceiver` uygulanabilir. Güncellemede aynı APK imzası korunur.
