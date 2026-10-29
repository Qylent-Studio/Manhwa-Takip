# Sorun giderme

## Paket bulamıyorum

İlk herkese açık paketler henüz yayımlanmadı. Hazır olduklarında
[GitHub Releases](https://github.com/Qylent-Studio/Manhwa-Takip/releases)
sayfasında yer alacak. Desteklenen sistemler Android 8+ ve Windows 11'dir.

## Kaynak açılmıyor veya kapak görünmüyor

- Webtoonhatti ve Manga-TR HTTP 403 döndürebilir; bu bir kaynak erişim
  kısıtlaması olabilir. Kısıtlamanın uygulama tarafından kaldırılması garanti edilmez.
- Turkmanga kapak bağlantıları geçersiz olabilir; bağlantıyı kontrol edin.
- PC'de WebP işleme Pillow ile yapılır. Bozuk görsel, geçersiz bağlantı veya
  ağ hatası yine de kapağın yüklenmesini engelleyebilir.
- Hata bildirirken özel bağlantılar yerine `https://example.invalid/eser`
  gibi gerçek olmayan örnekler kullanın.

## GPT JSON içe aktarılamıyor

- Çıktının geçerli JSON olduğunu ve her eserde `title` bulunduğunu kontrol edin.
- Yeni eserde durum verilmemişse `PLANNED` atanır; mevcut eserin belirtilmeyen
  alanları korunur. Paylaşım varsayılanı yalnızca addır; ek alanları isteğe bağlı seçin.
- Önizlemeyi kontrol edin ve kaydı onaylayın; onaysız kayıt beklemeyin.
- GPT'den JSON alma ve uygulamaya aktarma elle yapılır; otomatik GPT yüklemesi yoktur.

## PC telefonu görmüyor

Windows Setup Python/Tk/Pillow içerir; ayrıca Python kurulumu gerekmez.
Google'ın resmî SDK Platform-Tools paketindeki ADB'yi kurup erişilebilir yapın.
Veri aktarımını destekleyen bir USB kablosu kullanın. PC üzerinden USB işlemi
yapacaksanız USB hata ayıklamayı açın ve telefondaki RSA yetkilendirme isteğini
kontrol edin. Bu ayarlar Android uygulamasını tek başına kullanmak için gerekmez.
Telefon üzerinde MCP işlemi yapmadan önce kullanıcı onayı alınmalıdır.
Yeni kurulumda çevrimdışı arayüz normaldir. **Bağlantılar → İlk eşleştirmeyi hazırla**
ile açık onay vererek ilk anahtarı oluşturun; **Tamamen çık** deyip yeniden açın.
MCP kurulumu ve anahtarı gösterme ayrı işlemlerdir; telefonda eşleştirme/paylaşım
onayını ayrıca tamamlayın. Normal kullanıcı için elle CLI komutu gerekmez.

## Android güncellemesi kurulmuyor

Mevcut uygulamayla yeni APK'nın imzası farklı olabilir. Güncellemelerde aynı
imzalama kimliği korunmalıdır. Sorunu aşmak için yedek almadan uygulamayı
kaldırmayın; kaldırma yerel verileri silebilir.
Yeni public release anahtarı eski debug kurulumuyla uyumlu olmayabilir.
Eski imzayla veri koruyan yerel güncelleme ayrı pakettir; imza hatasını veri silerek aşmayın.

## Windows SmartScreen uyarısı

Windows paketi imzasızdır. Kaynak/yayıncıyı ve indirilen dosyanın SHA256 değerini
güvenilir sürüm bilgisiyle karşılaştırın; doğrulayamadığınız dosyayı çalıştırmayın.
SmartScreen veya antivirüs korumalarını körlemesine devre dışı bırakmayın.

## Windows başlangıç ve pencere davranışı

Otomatik başlatma isteğe bağlıdır; ayarlardaki tercihi kontrol edin.
Pencere görünmüyorsa sistem tepsisini kontrol edin. Tema tercihini uygulama
ayarlarından gözden geçirin.

## Veri kaybını önleme

Kaldırma, yeniden kurulum ve cihaz değişimi öncesinde yedek alın. Hata
bildirimlerine parola, token, özel dosya yolu veya kişisel adres eklemeyin.
