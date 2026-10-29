# Manhwa Takip Bilgisayar

## Windows dağıtım paketi (0.10.0)

`Setup.exe` kullanıcı başına, yönetici izni istemeden `%LOCALAPPDATA%\Programs\ManhwaTakip` altına kurar. Paket Python/Tkinter/Pillow/pystray/six içerir; sistem Python'u gerekmez. ADB dahil değildir: USB için Google'ın resmî Android platform-tools paketini ayrıca `%LOCALAPPDATA%\Android\Sdk\platform-tools` altına kur. Python dosyalarını doğrudan çalıştırmak yerine **Manhwa Takip.exe** kullan; bu başlatıcı paket yolunu ve izole `-I` modunu seçer. MCP yapılandırma yardımcısı da paketli konsol Python'u ve `-I` kullanır.

Kurulum/eşleştirme otomatik anahtar üretmez, mevcut anahtarı değiştirmez, telefon paylaşımını veya Windows başlangıcını açmaz. İlk eşleştirme şimdilik **manuel kullanıcı adımıdır**: yalnızca yeni yerel anahtar oluşturmayı açıkça istiyorsan kurulum klasöründe `runtime\python.exe -I bridge\bridge.py --init` çalıştır; sonra Bağlantılar → eşleştirme anahtarı yardımcısından telefonuna kendin aktar. `--init` mevcut anahtarı ezmez. Bu komutu otomatik test/kurulum çalıştırmaz.

Kullanıcı verileri `%LOCALAPPDATA%\ManhwaTakipCompanion\data`, anahtar/özel bağlantı ayarları `%LOCALAPPDATA%\ManhwaTakip` altında kalır; güncelleme/kaldırıcı bu klasörleri silmez. Uygulama kaldırılmadan önce tepsiden **Tamamen çık** seç. Setup/başlatıcı Windows kod imzası içermez. Temiz Windows VM'de gerçek kurulum/yükseltme/kaldırma kabulü ayrı yayın kapısıdır; yalnızca payload self-test'i bu kabulün yerine geçmez.

Derleme: temiz, sabitlenmiş build venv'i ve resmî Inno Setup 7.1.0 ile `scripts/build-windows.ps1`. Çıktı `artifacts/windows-0.10.0/Setup.exe`, SHA-256 ve byte/hash manifestidir. `scripts/check_windows_payload.py` izole geçici LOCALAPPDATA/PATH ile runtime, WebP alt süreci ve EXE self-test'ini kontrol eder.

## Kaynaktan geliştirici kurulumu

Windows'ta `install.ps1` çalıştırılır. Mevcut Python 3/Tkinter, Android platform-tools ve telefonun mevcut MCP eşleştirmesi kullanılır; anahtar üretilmez. Masaüstündeki **Manhwa Takip.exe** dosyasını açıp USB telefonu bağla. EXE konsol açmadan kurulu uygulamayı başlatır; eski kısayol varsa EXE'ye yönlendirilir. Tekrar açılış mevcut pencereye döner.

WebP kapaklar için uygulamayı çalıştıran Python ortamında `requirements-runtime.txt` içindeki sabitlenmiş Pillow gerekir (MIT-CMU; `licenses/Pillow-LICENSE.txt`). Ayrı yerel venv oluşturup açık kurulum adımı olarak `<venv>/Scripts/python.exe -m pip install -r companion/requirements-runtime.txt` çalıştırılır; uygulama kendiliğinden pip/indirme çalıştırmaz. Bu görevde geliştirme ortamı `.superpowers/sdd/public-release/runtime-venv` kullanır; dağıtım/installer entegrasyonu sonraki paketleme görevine aittir. Pillow yoksa WebP reddedilir, mevcut kapak korunur; çevrimdışı JPEG/PNG ve eski manuel dönüştürme yolu değişmez.

EXE mevcut Python kurulumunu kullanır; Python taşınır/kaldırılırsa `install.ps1` yeniden çalıştırılmalıdır. Başlatıcı Windows'un mevcut .NET Framework derleyicisiyle oluşturulur. `Manhwa Takip.exe --self-test` yalnızca geçici örnek verilerle pencere kontrolü yapar.

Arayüz okuma takibine odaklıdır: Okuma alanım ekranında kapaklı kartlar, belirgin son bölüm ve durum bilgisi bulunur. Kayıtlı kapaklar seçim/tıklama gerektirmeden arka planda yüklenir; filtre değişiminde önbellekten gösterilir. Ayrıntılar / düzenle ile açılan form Kapat ile taslağı koruyarak gizlenir. Fluent esintili sabit sol menüden Yedekler, AI paylaşımı ve Ayarlar açılır; Ayarlar ilk veri alındığında kullanılabilir. Minimum pencere 1000×680; kartlar genişliğe göre yeniden yerleşir. Mevcut Tkinter kullanılır; yeni bağımlılık yoktur.

- Kablo algılanınca tam veriler alınır ve `%LOCALAPPDATA%/ManhwaTakipCompanion/data/devices/` altında telefon başına tarihli ZIP tutulur. Taslaklar ayrı JSON dosyasıdır. Program Windows başlangıcında çalışmaz.
- Okuma alanım kartından düzenle; **Telefona aktar** seç. Telefon kayıt sürümü değişmişse tüm paket reddedilir. AI/MCP ve AI JSON içe aktarma onayı korunur.
- Yedekler JSON/ZIP kaydedilir veya telefona eksikleri eklemek için aktarılır. Yedekler parolasızdır; API/eşleştirme anahtarları dahil değildir.
- AI paylaşımı durum filtresi, okuma profili/geçmiş, öneri veya özet amacıyla JSON/ZIP üretir. Sohbetler ve anahtarlar dahil olmaz; harici hizmete kendiliğinden göndermez.

Telefon 0.8.0+ olmalıdır. Uygulama açıkken yalnızca bir yetkili fiziksel USB telefon bağla; emülatör/TCP bağlantılar otomatik seçilmez. Telefon ekranı aktarım süresince kısa süre açılır.

Kontroller: `python -m unittest discover -s companion`, `python companion/app.py --self-test`. Gerçek USB kabulü: `python companion/usb_acceptance.py --serial <cihaz>`; test APK'sı gerekir, yalnızca üretilmiş geçici kayıt yazılır ve sonra temizlenir. Eşleştirme anahtarı çıktıya yazılmaz.

Gerçek pencere/otomatik ve tekrarlanan USB okuma/tarihli yedek kabulü: `python companion/gui_acceptance.py`; fiziksel veri yazmaz, geçici bilgisayar klasörü kullanır. Kabul sonuçları: `../artifacts/acceptance-companion.md`.
