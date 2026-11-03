# Mitt-go

[English](./docs/README_en.md) | 中文

<p align="center">
  <img src="./docs/icon.png" alt="Mitt-go" width="140">
</p>

一款基于 Go + cgo 引擎的安卓 MITM 应用


## 下载App

从 [Releases](https://github.com/Phantasmic-cloud/Mitt-go/releases) 下载最新版：

```text
Mitt-go-arm64-v8a-release-<版本号>.apk
```


## 从源码构建

需要 JDK 17、Android SDK（`compileSdk 37`）、Go 1.23、Android NDK。

```bash
export ANDROID_NDK_HOME=<ndk 路径>

./scripts/build-go-ndk.sh        # 编内核，产出 libmittgocore.so
gradle :app:assembleDebug
gradle :app:assembleRelease      # R8 + 资源压缩
```

## 目录

```
app/       Android 外壳（Kotlin + Compose + Miuix）
core/      Go 内核：MITM、记录、域名过滤、旁路上传
scripts/   构建脚本
docs/      文档
example/   配置示例
```

实现细节和内核 API 见 [`AGENTS.md`](./AGENTS.md)。


## 致谢

- Miuix components — Apache License 2.0
- [go-mitmproxy](https://github.com/lqqyt2423/go-mitmproxy) — MIT License
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — Apache License 2.0
- [Project SEKAI](https://pjsekai.sega.jp/)

以上名称与商标归各自所有者所有。

## 许可证

[Apache License 2.0](./LICENSE)