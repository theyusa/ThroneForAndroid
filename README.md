<div align="center">

# Throne for Android · `yusa` fork

[🇬🇧 English](README.md) · [🇹🇷 Türkçe](README.tr.md)

</div>

> 🔀 **This repository is a fork of [throneproj/ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid).**
> It adds exactly **one feature** on top: **Hotspot Gateway** — automatic SOCKS5 proxying through your Wi-Fi gateway.

<p align="center">
  <a href="#"><img alt="Android" src="https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84?logo=android&logoColor=white&style=for-the-badge"></a>
  <a href="#"><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/License-GPL--3.0-blue?logo=gnu&logoColor=white&style=for-the-badge"></a>
  <a href="https://github.com/throneproj/ThroneForAndroid"><img alt="Upstream" src="https://img.shields.io/badge/upstream-throneproj%2FThroneForAndroid-1f6feb?logo=github&logoColor=white&style=for-the-badge"></a>
  <a href="#daily-upstream-sync"><img alt="Upstream sync" src="https://img.shields.io/badge/upstream--sync-daily-2088FF?logo=githubactions&logoColor=white&style=for-the-badge"></a>
</p>

Android GUI proxy client — powered by the [Sing-box](https://github.com/SagerNet/sing-box) core. Supports Android 7.0 (API 24) and up, plus Android TV.

This fork keeps **all of Throne's features** and adds a single, carefully isolated addition: automatically discovering a SOCKS5 proxy on your phone's Wi-Fi gateway and attaching it as a managed profile.

<p align="center">
  <a href="https://github.com/theyusa/ThroneForAndroid/releases">
    <img alt="GitHub Releases" src="https://img.shields.io/badge/%E2%AC%87%EF%B8%8F%20Download-GitHub%20Releases-2088FF?logo=github&logoColor=white&style=for-the-badge" />
  </a>
</p>

---

## 🔥 What this fork adds — Hotspot Gateway

Scenario: another device (e.g. your computer) serves a SOCKS5 proxy over its own hotspot. The `yusa` fork lets your phone auto-detect that proxy and tunnel through it with a single tap.

- 📡 **Auto-discovers the gateway** — resolves the Wi-Fi gateway IP by itself (no manual IP entry).
- 🔢 **Manual input** — port (default `2080`), username and password are entered by hand in settings.
- ✅ **Anonymous discovery** — first verifies it really is a SOCKS5 server without sending any credentials.
- 🔐 **RFC1929 handshake** — a second, credential-carrying check validates the password.
- 🛡️ **Negative control** — first tries a random wrong credential; rejects the server if it accepts *every* credential (i.e. doesn't enforce auth).
- 📁 **Managed profile** — creates a fixed-name SOCKS5 profile in a dedicated group for the verified gateway and auto-selects it.
- 🔁 **Automatic fallback** — returns to the previous profile when the hotspot disappears (warns, or optionally stops the service, if there's nothing to fall back to).
- 🧭 **Cross-process monitoring** — also watches from the `:bg` process; state stays consistent via Room/DB even though the main and background processes are separate JVMs.
- 🔒 **Backup protection** — hotspot credentials (username/password) never enter a backup; the managed profile's password is masked on export.
- 🪵 **Debug logging** — warnings/errors are written to the app's own **Logs** screen (CoreLog); log lines never carry credentials.

> ⚠️ **Sharing/QR is intentionally absent.** If "share" is ever needed, the standard `socks5://user:pass@host:port` URI will be used; no custom scheme.

---

## ✨ Throne's features

All of Throne's existing features, plus the Hotspot Gateway above:

- 🗂️ Groups and subscriptions that work like desktop Throne: per-group subscription options (filter, cleanup, URL test after update), front/landing proxy, HWID headers, scheduled updates with change reports
- 💾 Backups in the desktop `.thrbackup` format (restorable in both directions): save, share, or sync via WebDAV
- 🧩 Route profiles shared with desktop + Android-specific rule conditions: apps, Wi-Fi network (SSID/BSSID), network type, and metered networks
- ⚡ Auto Selector profiles that rank and auto-switch the best server of one or many groups
- ☁️ Cloudflare WARP: sign-up (WireGuard or MASQUE identity) and built-in WARP mode
- 🧪 URL, IP/country and speed tests + a batch test panel (live progress, latency histogram, fastest-first)
- 📊 Built-in sing-box dashboard (live traffic, connections, logs)
- ✏️ JSON editor with sing-box schema validation, completion and formatting
- 🧩 Home-screen widgets, quick-settings tile, and server switching from the notification
- 📺 Android TV: launcher banner, D-pad navigation, previous/next server

---

## 🔌 Supported protocols

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

## 📥 Subscription formats

Share links, various sing-box JSON representations, and Shadowsocks + Clash support limited to the v2rayN link format. Only a subscription's outbounds (servers) are imported; routing rules are ignored. Deeplinks are also supported ([documentation](https://throneproj.github.io/advanced/deeplinks/)).

---

## 🔄 Daily upstream sync

This fork ships a GitHub Action to stay in sync with upstream (`throneproj/ThroneForAndroid`): [`.github/workflows/sync-upstream.yml`](.github/workflows/sync-upstream.yml).

- 🕒 Runs every day at **03:00 UTC** (or manually via `workflow_dispatch`).
- 🔍 Checks whether upstream `main` has new commits.
- 🔁 If so, rebases the single Hotspot Gateway patch commit onto the new upstream.
- ✅ If clean, updates the `hotspot-gateway` branch with `push --force-with-lease`.
- ⚠️ On conflict, aborts the rebase and opens an issue with a resolution hint.

This keeps the patch as a single, clean commit that is automatically carried forward on every upstream update.

---

## 🙏 Credits

This fork is built on top of [throneproj/ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid).

- [throneproj/sing-box](https://github.com/throneproj/sing-box) — a fork of [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [throneproj/Xray-core](https://github.com/throneproj/Xray-core) — a fork of [XTLS/Xray-core](https://github.com/XTLS/Xray-core)

Android GUI:

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)

---

## ❓ FAQ

**How is this different from NekoBox for Android?** <br/>
Throne started as a fork of NekoBox for Android. v2.0.0 replaced the core and data model with desktop Throne's. This `yusa` fork adds only the Hotspot Gateway feature on top of Throne.

**Where do downloadable route profiles/rulesets come from?** <br/>
From the [routeprofiles](https://github.com/throneproj/routeprofiles) repository — the same place desktop Throne uses.

**Why are some servers skipped on link/subscription import?** <br/>
As on desktop, Extra Core, Chain and Auto Selector profiles are never imported from a link/subscription/shared route profile. Nodes the app can't run are also skipped, and the import reports what it skipped.

---

## 📜 License

[GNU General Public License v3.0](LICENSE)
