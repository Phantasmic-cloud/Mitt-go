# Mitt-go

English | [中文](../README.md)

<p align="center">
  <img src="./icon.png" alt="Mitt-go" width="140">
</p>

An Android MITM app built on a Go + cgo engine.

## Download

Get the latest build from [Releases](https://github.com/Phantasmic-cloud/Mitt-go/releases):

```text
Mitt-go-arm64-v8a-release-<version>.apk
```

## Build from source

Requires JDK 17, Android SDK with `compileSdk 37`, Go 1.26+ and the Android NDK.

```bash
export ANDROID_NDK_HOME=<ndk path>

./scripts/build-go-ndk.sh        # builds libmittgocore.so
gradle :app:assembleDebug
gradle :app:assembleRelease      # R8 + resource shrinking
```

## Layout

```
app/       Android shell
core/      Go core: MITM, records, domain filter, async upload
scripts/   build scripts
docs/      docs
example/   config examples
```

Implementation notes and the core API are in [`AGENTS.md`](../AGENTS.md).

## Credits

- Miuix components — Apache License 2.0
- [go-mitmproxy](https://github.com/lqqyt2423/go-mitmproxy) — MIT License
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — Apache License 2.0
- [Project SEKAI](https://pjsekai.sega.jp/)

All names and trademarks belong to their respective owners.

## License

[Apache License 2.0](../LICENSE)