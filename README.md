# Zapret Mobile (non-root)

Android app that combines a zapret-style DPI bypass with encrypted DNS (DoH), **without root**.

```
apps → TUN (VpnService) → hev-socks5-tunnel → 127.0.0.1:1080 (byedpi: applies the strategy) → internet
DNS  → hev mapped-DNS → domain names → byedpi → zp_resolver.c → DnsEngine (plain / DoH / auto)
```

## Features
- **Custom strategies**: create, edit, delete, select; export/import as JSON text. Built-in presets included.
- **App exclusion**: "all apps except checked" or "only checked apps" (per-app VPN rules).
- **DNS**: Auto (probes the DoH resolvers, picks the fastest working one), DoH (pick a resolver or custom URL), Plain DNS.

## Strategy syntax
Strategies use [byedpi](https://github.com/hufrea/byedpi) options, e.g. `--disorder 1 --tlsrec 1+s`,
`--fake -1 --ttl 8`, `--auto=torst --split 1+s`. `--ip`, `--port`, `--daemon`, `--pidfile`,
`--protect-path` and `--drop-sack` are rejected (managed by the app / need root).
Windows zapret `winws2` options (`badsum`, `seqovl`, raw-packet tricks, Lua) cannot run without root.

## Native sources (git submodules)
```
app/src/main/jni/byedpi                https://github.com/hufrea/byedpi            (MIT)
app/src/main/jni/hev-socks5-tunnel     https://github.com/heiher/hev-socks5-tunnel (MIT, needs --recursive)
```
`git submodule update --init --recursive`, then build with Gradle (JDK 17, Android SDK 35, NDK 27):
`gradle :app:assembleDebug` — or run the **Build APK** workflow on GitHub.
