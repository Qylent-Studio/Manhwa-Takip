# Gizlilik ve veri kullanımı

## Yerel veriler

Okuma listesi ve uygulama verileri yerel olarak tutulur. Yerel depolama,
cihazınıza veya yedeklerinize erişen kişilere karşı tek başına koruma sağlamaz.
Uygulamayı kaldırmadan veya cihaz değiştirmeden önce yedek alın.
Yedeklerinizi güvenli bir yerde tutun; paylaşmadan önce içeriğini kontrol edin.
Yeni kurulumun çevrimdışı kütüphane arayüzü PC veya AI bağlantısı gerektirmez.

## Kaynak siteler ve görseller

Kaynak bağlantılarını açmak veya uzak kapakları almak ilgili sitelere ağ
istekleri gönderilmesine neden olabilir. Bu sitelerin gizlilik koşulları ve
erişim kısıtlamaları kendilerine aittir. Yerel veri kullanımı, uygulamanın
hiç ağ isteği yapmadığı anlamına gelmez.

## GPT ve JSON

Uygulama verilerinizi GPT'ye otomatik olarak yüklemez. GPT kullanımı ve JSON
çıktısını uygulamaya aktarma kullanıcı tarafından elle yapılır. Harici bir
AI hizmetine gönderdiğiniz içerik o hizmetin koşullarına tabidir.

AI dışa paylaşımında `title` zorunludur; varsayılan yalnızca eser adıdır.
Ek alanlar isteğe bağlıdır; göndermeden önce seçimi ve dosyayı inceleyin.
JSON içe aktarmada `title` zorunludur; yeni eserde durum yoksa `PLANNED` kullanılır.
Mevcut eserin belirtilmeyen alanları korunur.
Önizlemeyi inceleyip onaylamadan kayıt yapılmaz. Gereksiz kişisel bilgileri,
parolaları veya erişim anahtarlarını AI istemlerine ve JSON dosyalarına koymayın.

## Telefon erişimi

PC üzerinden USB işlemleri USB hata ayıklama ve telefonun RSA onayını gerektirir.
Android uygulamasının bağımsız kullanımı bunları gerektirmez. Telefon üzerinde
MCP işlemleri için ayrıca kullanıcı onayı alınmalıdır; bağlantı yetkisi genel
işlem izni değildir. Güvenmediğiniz bilgisayarlara USB yetkisi vermeyin.
Windows'taki **İlk eşleştirmeyi hazırla** yalnızca açık onayla ilk yerel anahtarı
oluşturur; sonrasında **Tamamen çık** ve yeniden açma gerekir. MCP kurulumu ve
anahtar gösterimi, telefonda eşleştirme ve paylaşım onayından ayrıdır; paylaşımı açmaz.

## Windows tercihleri

Tema, sistem tepsisi ve otomatik başlatma Windows kullanım seçenekleridir.
Otomatik başlatma isteğe bağlıdır.

## Destek paylaşımı

Hata bildirirken kişisel adresleri, cihaz bilgilerini, özel dosya yollarını ve
kimlik bilgilerini ayıklayın. Bağlantı örneklerinde
`https://example.invalid/eser` gibi gerçek olmayan adresler kullanın.
