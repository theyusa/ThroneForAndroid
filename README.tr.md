<div align="center">

# Throne for Android · `yusa` fork

[🇬🇧 English](README.md) · [🇹🇷 Türkçe](README.tr.md)

</div>

> 🔀 **Bu depo, [throneproj/ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid) projesinin bir fork'udur.**
> Üzerine yalnızca **tek bir özellik** ekler: **Hotspot Gateway** — Wi-Fi ağ geçidi üzerinden otomatik SOCKS5 proxy.

<p align="center">
  <a href="#"><img alt="Android" src="https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84?logo=android&logoColor=white&style=for-the-badge"></a>
  <a href="#"><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge"></a>
  <a href="LICENSE"><img alt="Lisans" src="https://img.shields.io/badge/Lisans-GPL--3.0-blue?logo=gnu&logoColor=white&style=for-the-badge"></a>
  <a href="https://github.com/throneproj/ThroneForAndroid"><img alt="Upstream" src="https://img.shields.io/badge/upstream-throneproj%2FThroneForAndroid-1f6feb?logo=github&logoColor=white&style=for-the-badge"></a>
  <a href="#günlük-upstream-senkronizasyonu"><img alt="Upstream sync" src="https://img.shields.io/badge/upstream--sync-daily-2088FF?logo=githubactions&logoColor=white&style=for-the-badge"></a>
</p>

Android GUI proxy aracı — [Sing-box](https://github.com/SagerNet/sing-box) çekirdeğiyle güçlendirilmiştir. Android 7.0 (API 24) ve üstü ile Android TV desteklenir.

Bu fork, **Throne'un tüm özelliklerini** korur ve üzerine tek bir, dikkatle izole edilmiş ekleme yapar: telefonunuzun Wi-Fi ağ geçidindeki bir SOCKS5 proxy'yi otomatik bulup yönetilen bir profil olarak bağlamak.

<p align="center">
  <a href="https://github.com/theyusa/ThroneForAndroid/releases">
    <img alt="GitHub Releases" src="https://img.shields.io/badge/%E2%AC%87%EF%B8%8F%20İndir-GitHub%20Releases-2088FF?logo=github&logoColor=white&style=for-the-badge" />
  </a>
</p>

---

## 🔥 Bu fork ne ekliyor? — Hotspot Gateway

Senaryo: bir cihaz (ör. bilgisayar veya başka bir cihaz) kendi hotspot'u üzerinden SOCKS5 proxy sunuyor. `yusa` fork, telefonun o proxy'yi otomatik tanıyıp tek dokunuşla tünel açmasını sağlar.

- 📡 **Ağ geçidini otomatik bulur** — Wi-Fi ağ geçidi IP'sini kendisi çözer (elle IP girmek yok).
- 🔢 **Elle giriş** — port (varsayılan `2080`), kullanıcı adı ve parola ayarlardan elle yazılır.
- ✅ **Kimliksiz keşif** — önce hiçbir kimlik bilgisi göndermeden gerçekten bir SOCKS5 olup olmadığını doğrular.
- 🔐 **RFC1929 el sıkışması** — ikinci, kimlik taşıyan ayrı bir kontrolle parolayı doğrular.
- 🛡️ **Negatif kontrol** — rastgele yanlış bir kimlikle önce dener; sunucu *her* kimliği kabul ediyorsa (kimlik zorlamıyorsa) reddeder.
- 📁 **Yönetilen profil** — doğrulanan gateway için ayrı bir grupta, sabit adlı bir SOCKS5 profili oluşturur ve otomatik seçer.
- 🔁 **Otomatik geri dönüş** — hotspot kaybolunca önceki profiline döner (dönülecek profil yoksa uyarır veya isteğe bağlı olarak servisi durdurur).
- 🧭 **Çapraz süreç izleme** — `:bg` sürecinde de kesintisiz izlenir; ana süreç ve arka plan süreç ayrı JVM olsa da durum Room/DB üzerinden tutarlı kalır.
- 🔒 **Yedekleme koruması** — hotspot kimlik bilgileri (kullanıcı adı/parola) yedeğe asla girmez; yönetilen profilin parolası dışa aktarımda maskelenir.
- 🪵 **Hata ayıklama** — uyarı/hata noktaları uygulamanın kendi **Günlükler** ekranına (CoreLog) yazılır; log satırları kimlik bilgisi taşımaz.

> ⚠️ **Paylaşım/QR bilinçli olarak yok.** İleride "paylaş" gerekirse standart `socks5://user:pass@host:port` URI'si kullanılır; özel bir scheme uygulanmaz.

---

## ✨ Throne'un özellikleri

Throne'un mevcut tüm özellikleri + yukarıdaki Hotspot Gateway:

- 🗂️ Masaüstü Throne gibi çalışan gruplar ve abonelikler: grup başına abonelik seçenekleri (filtre, temizlik, güncelleme sonrası URL testi), front/landing proxy, HWID başlıkları, değişiklik raporlu planlı güncellemeler
- 💾 Masaüstü `.thrbackup` formatında yedekler (iki yönde de geri yüklenebilir): kaydet, paylaş veya WebDAV ile eşitle
- 🧩 Masaüstüyle paylaşılan route profilleri + Android'e özel kural koşulları: uygulamalar, Wi-Fi ağı (SSID/BSSID), ağ türü ve metered ağlar
- ⚡ Bir veya birden çok grubun en iyi sunucusunu sıralayıp otomatik geçen Auto Selector profilleri
- ☁️ Cloudflare WARP: kayıt (WireGuard veya MASQUE kimliği) ve yerleşik WARP modu
- 🧪 URL, IP/ülke ve hız testleri + toplu test paneli (canlı ilerleme, gecikme histogramı, en hızlılar)
- 📊 Yerleşik sing-box dashboard'u (canlı trafik, bağlantılar, loglar)
- ✏️ sing-box şema denetimi, tamamlama ve biçimlendirmeli JSON editörü
- 🧩 Ana ekran widget'ları, hızlı ayarlar kısayolu ve bildirimden sunucu değiştirme
- 📺 Android TV: launcher banner'ı, D-pad gezinme, önceki/sonraki sunucu

---

## 🔌 Desteklenen protokoller

| | | | |
|---|---|---|---|
| 🧦 SOCKS5 | 🌐 HTTP(S) | 🛡️ Shadowsocks | 🐴 Trojan |
| 💬 VMess | ✉️ VLESS | ⚡ Xray VLESS | 🚀 TUIC |
| 🌀 Hysteria 1/2 | 🔗 AnyTLS | 🪨 Mieru | 🐚 Snell |
| 🦋 NaïveProxy | 🍹 Juicity | 🚇 TrustTunnel | 🎭 ShadowTLS |
| 🔐 WireGuard | 🔐 AmneziaWG | 🎭 MASQUE | 🔑 SSH |
| 🔒 OpenVPN | 🔓 OpenConnect | ➡️ Direct | ⛓️ Chain |
| 🧩 Custom outbound | ✨ Auto Selector | | |

---

## 📥 Abonelik formatları

Paylaşım linkleri, çeşitli sing-box JSON temsilleri, v2rayN link formatı ile sınırlı Shadowsocks ve Clash desteği. Bir aboneliğin yalnızca outbound'ları (sunucuları) içe aktarılır; yönlendirme kuralları yok sayılır. Deeplink'ler de desteklenir ([dokümantasyon](https://throneproj.github.io/advanced/deeplinks/)).

---

## 🔄 Günlük upstream senkronizasyonu

Bu fork, upstream (`throneproj/ThroneForAndroid`) ile senkron kalması için bir GitHub Action içerir: [`.github/workflows/sync-upstream.yml`](.github/workflows/sync-upstream.yml).

- 🕒 Her gün **03:00 UTC** (veya elle `workflow_dispatch`) çalışır.
- 🔍 Upstream `main`'de yeni commit var mı diye bakar.
- 🔁 Varsa, tek Hotspot Gateway patch commit'ini yeni upstream üzerine `rebase` eder.
- ✅ Temizse `push --force-with-lease` ile `hotspot-gateway` dalını günceller.
- ⚠️ Çakışma çıkarsa rebase'i iptal eder ve çözüm ipucuyla bir issue açar.

Böylece patch tek, temiz bir commit olarak kalır; upstream her güncellendiğinde otomatik taşınır.

---

## 🙏 Krediler

Bu fork, [throneproj/ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid) üzerine kuruludur.

- [throneproj/sing-box](https://github.com/throneproj/sing-box) — [SagerNet/sing-box](https://github.com/SagerNet/sing-box) fork'u
- [throneproj/Xray-core](https://github.com/throneproj/Xray-core) — [XTLS/Xray-core](https://github.com/XTLS/Xray-core) fork'u

Android GUI:

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)

---

## ❓ SSS

**Bu proje NekoBox for Android'den nasıl farklı?** <br/>
Throne, NekoBox for Android fork'u olarak başladı. v2.0.0, çekirdeği ve veri modelini Throne masaüstününkiyle değiştirdi. Bu `yusa` fork ise Throne'un üzerine yalnızca Hotspot Gateway özelliğini ekler.

**İndirilebilir route profilleri/ruleset'ler nereden geliyor?** <br/>
[routeprofiles](https://github.com/throneproj/routeprofiles) deposundan — Throne masaüstünün kullandığı yer.

**Neden bazı sunucular link/abonelik içe aktarırken atlanıyor?** <br/>
Masaüstünde olduğu gibi, Extra Core, Chain ve Auto Selector profilleri link/abonelik/paylaşılan route profilinden asla içe aktarılmaz. Uygulamanın çalıştıramadığı düğümler de atlanır ve içe aktarma neyi atladığını bildirir.

---

## 📜 Lisans

[GNU General Public License v3.0](LICENSE)
