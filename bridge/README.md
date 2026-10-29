# Manhwa USB / MCP köprüsü

Kütüphane telefonda kalır. Windows köprüsü yalnızca kullanıcının açtığı paylaşımın
bellekteki kopyasını tutar; MCP değişiklikleri telefonda ayrıca onaylanır.
AI sağlayıcısının anahtarını MCP yapılandırmasına eklemeyin.

## Hazırlık

Python 3, Android SDK platform-tools/ADB ve USB hata ayıklama izni gerekir.
Windows'ta işlemler kurulu **Manhwa Takip.exe** uygulamasının **Bağlantılar**
sayfasından yapılır; ayrı bir betik veya kısayol dosyası gerekmez.

- **İlk eşleştirmeyi hazırla**: açık onayınızla, yalnızca yoksa yeni yerel DPAPI
  anahtarı oluşturur. Mevcut anahtarı değiştirmez.
- **Mevcut eşleştirme anahtarı**: anahtarı yalnızca yerel pencerede gösterir;
  telefondaki eşleştirme alanına kendiniz girersiniz. Anahtarı kaynak koduna,
  loglara veya istemci ayarına yazmayın. Kopyalama yalnızca açık kullanıcı eylemiyle olur.
- **USB başlat** / **MCP başlat** / **MCP yeniden başlat**: tek yetkili fiziksel USB
  cihazıyla köprüyü hazırlar. Telefonda paylaşımı ayrıca kendiniz başlatın;
  uygulama paylaşımı kendiliğinden açmaz.

İsteğe bağlı uzak AI için kendi özel bağlantı ayarlarınızı kullanın; kaynak dağıtımı
canlı sunucu adresi veya SSH komutu içermez. Uzak AI hizmetini ayrıca başlatmanız
gerekirse komut satırından `python bridge/start_connections.py --mode ai` çalıştırın.

## Yerel STDIO istemcisi

**Bağlantılar → MCP istemci kurulumu** anahtarsız MCP JSON ayarını gösterir. Var olan
sunucuları korur, isim çakışmasını reddeder ve değişiklikten önce yedek alır.
Elle kurulum için:

- Ad: `manhwa_takip`; taşıma: STDIO; komut: `python` (gerekirse tam Python yolu).
- Argüman: `C:/path/to/project/bridge/bridge.py` (tek argüman).
- Çalışma klasörü: `C:/path/to/project`.
- TOML kullanan istemciler için `chatgpt-local-mcp.toml.example` tablosunu uyarlayın;
  mevcut yapılandırmanın tamamını değiştirmeyin veya aynı sunucuyu iki kez eklemeyin.

İstemciyi yeniden yükleyip `list_library`, `propose_change`, `proposal_status`
araçlarını kontrol edin. Destek istemcinin sürümüne ve hesap özelliklerine bağlıdır.
Yerel bağlantı için tünel veya yeni OpenAI API anahtarı gerekmez.

Telefonda bağlantıyı kapatmak erişimi sonlandırır. USB kopuksa backend'i yeniden
başlatın veya 15 dakikalık sürenin dolmasını bekleyin. Backend yeniden başlatılması
bellek kopyasını temizler; telefon kayıtlarını ve eşleştirme anahtarını silmez.

## Sentetik testler

```powershell
python -m unittest discover -s bridge -p 'test_*.py' -v
```

Test APK'sına yönelik USB/eşleştirme yardımcıları yalnızca açık kullanıcı onayıyla
çalıştırılmalıdır; normal MCP kurulumu için gerekli değildir.
