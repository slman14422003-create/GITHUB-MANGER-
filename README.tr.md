# GitHub Manager

Diller: [English](README.md) | [العربية](README.ar.md) | [Français](README.fr.md) | [Español](README.es.md) | [Türkçe](README.tr.md) | [Русский](README.ru.md)

Guvenli kullanim ve kotuye kullanima karsi koruma: [SAFE_USE.md](SAFE_USE.md)

GitHub Manager, telefondan GitHub depolarini, dosyalari, Actions is akislarini, issue'lari ve surumleri (release) yonetmek icin yazilmis yerel bir Android uygulamasidir (Java).

## Ozellikler

Depolar ve dosyalar
- Depolarinizi listeleyin, arayin, filtreleyin ve eskilerini temizleyin.
- Telefondan klasorlerin tamamini yapisini koruyarak yukleyin. Uygulama ayni olan dosyalari atlar, .gitignore dosyasina uyar, yuklemeden once onizleme (yeni, degisen, ayni) gosterir ve hata sonrasinda kaldigi yerden devam eder.
- Dosya, klasor veya depolarin tamamini duraklatma, devam ettirme ve iptal secenekleriyle indirin. Aktarimlar bildirimle birlikte arka planda surer.
- Liste ve izgara gorunumlu cihaz dosya yoneticisi, surukleyerek coklu secim, arama ve 30 gun sonra kendiliginden bosalan uygulama ici cop kutusu.
- Soz dizimi renklendirme, satir numaralari, geri al ve yinele, kod arama, Markdown gosterimi ve satir farklari sunan kod duzenleyici.

Dallar ve commit'ler
- Dal olusturun, birlestirin, silin ve varsayilan dali belirleyin.
- Commit gecmisine goz atin ve her commit'in farkini gorun.

GitHub Actions
- Is akislarini listeleyin ve calistirmalari her job, her adim ve canli sure sayaciyla anlik izleyin.
- Adimlara gore gruplama, satir numaralari ve yalnizca hatalar filtresi olan log goruntuleyici.
- Bir is akisini girdilerle elle baslatin ve eski calistirmalari temizleyin.
- Artifact'leri indirin; APK otomatik cikarilir.
- Tek ekrandan surum derleyin ve yayinlayin.
- SEMO AI: is akisi baslamadan once calisan cevrimdisi on kontrol. Dengesiz parantezleri ve guvenli bicim hatalarini bulur, yalnizca kesin olanlari duzeltir. Ag cagrisi yapmaz, model kullanmaz.

Issue'lar, surumler ve is birligi
- Issue'lar: listeleme, filtreleme, olusturma, yorum ve duzenleme.
- Surumler: goruntuleme, yayinlama, silme ve dosyalarini indirme.
- Is birlikcileri: kisileri davet edin ve yetki duzeylerini degistirin.
- Depo istatistikleri: diller, trafik ve katkida bulunanlar.
- Depo guvenlik ayarlari: dal korumasi, gizli bilgi taramasi, push korumasi, Dependabot guvenlik guncellemeleri ve varsayilan Actions izinleri.
- Sifreli Actions gizli bilgileri ve imzalama anahtari kurulumu.

Guvenlik ve gizlilik
- Erisim token'i Android Keystore ile saklanir.
- Telefonun parmak izi, deseni veya PIN'i ile istege bagli uygulama kilidi.
- Uygulama icinde ekran goruntusu engellenir.
- Yalnizca HTTPS; yalnizca sistem sertifikalarina guvenilir. Yedekleme kapalidir.
- APK derleme sirasinda muhurlenebilir ve uygulama baslarken muhru dogrular.
- Guncellemeler SHA-256 ve imza sertifikasi ile dogrulanir.

Diger
- GitHub'in yavas veya engelli oldugu aglar icin istege bagli Cloudflare Worker yansisi.
- Arapca ve Ingilizce arayuz, acik ve koyu temalar, birden fazla hesap.

## Etkinlestirme

1. APK'yi kurun
   - APK'yi bu deponun Releases sayfasindan indirip kurun. Dosyayi actiginiz uygulama icin "Bilinmeyen uygulamalari yukle" iznini verin.
   - Android 7.0 (API 24) veya daha yenisi gerekir.

2. Giris yapin (birini secin)
   - Device flow: Google veya e-posta secenegine dokunun, GitHub bir kod gosterir, kodu resmi GitHub sayfasina girin. Sifreniz uygulamadan gecmez. Device Flow'un acik oldugu bir GitHub OAuth App gerekir; Client ID'si tasarim geregi geneldir. Derlemeden once depo degiskeni GH_OAUTH_CLIENT_ID'ye (Settings, Secrets and variables, Actions, Variables) yazin veya uygulamada Client ID secenegiyle girin.
   - Kisisel erisim token'i: GitHub'da bir token olusturup uygulamaya yapistirin. Yalnizca gereken izinleri verin. Device flow su izinleri ister: repo, workflow, delete_repo, read:org, user, admin:public_key.

3. Istege bagli koruma
   - Ayarlar, Uygulama kilidi: acin ve gecikmeyi secin. Telefonda once bir ekran kilidi ayarlanmis olmalidir.

## GitHub Actions ile kaynaktan derleme

1. Kalici bir imzalama anahtari olusturun. "Generate signing key" is akisini yalnizca deponun OZEL (Private) bir kopyasinda calistirin; genel depoda calismayi reddeder. Artifact'i indirin, dosyalari cevrimdisi guvenli bir yerde saklayin ve artifact'i silin. Isterseniz tools/make-keystore.sh dosyasini kendi bilgisayarinizda calistirin.
2. Su depo gizli bilgilerini ekleyin: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
3. "Build APK" is akisini calistirin. Surum, Releases sayfasinda gorunur.
4. Keystore'u, sifreleri veya token'lari asla depoya yuklemeyin.

## Cloudflare Worker'i yayinlama (istege bagli)

Worker istekleri yalnizca GitHub sunucularina iletir ve gizli anahtarinizi tasimayan her istegi reddeder.

1. Node.js kurun ve worker klasorunde bir terminal acin.
2. Giris: npx wrangler login
3. Yayinlama: npx wrangler deploy
4. Uzun rastgele bir anahtar olusturun: openssl rand -hex 32
5. Gizli bilgi olarak kaydedin: npx wrangler secret put MIRROR_KEY ve anahtari yapistirin. Bu gizli bilgi olmadan Worker her istege 503 doner.
6. Uygulamada Ayarlar, Yansi (Mirror) bolumunu acin. Worker adresini ve anahtari girip Test'e basin.
7. Onerilir: Cloudflare panelinde workers.dev adresini kapatin, kendi alan adinizi kullanin ve istek sinirlama kurali ekleyin. Cloudflare hesabinizda iki adimli dogrulamayi acin.
8. Anahtar ifsa olursa ayni komutla hemen degistirin.

## GitHub kurallarina uyma

Tam koruma ve kotuye kullanim politikasi icin SAFE_USE.md dosyasini okuyun.
