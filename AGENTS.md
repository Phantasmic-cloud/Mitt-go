# AGENTS.md

## 这个仓库是什么

Mitt-go 是一个 Android 端的 MITM 抓包工具：拉起本地 VPN、用固定 CA 做 TLS 中间人、记录 HTTP(S) 流量，
并提供浏览、搜索、导出；命中的请求体/响应体可以另外 POST 到你自己的服务器（"旁路上传"），出站流量也可以
选择走上游代理。

| 路径 | 内容 |
|---|---|
| `app/` | Android 外壳：Kotlin + Jetpack Compose + Miuix |
| `core/` | Go 内核：`go-mitmproxy` + 记录库、域名过滤、旁路上传、上游代理 |
| `scripts/build-go-ndk.sh` | 用 NDK 把内核编成 `app/src/main/jniLibs/arm64-v8a/libmittgocore.so` |
| `.github/workflows/build.yml` | CI：编内核、打 release APK、打 tag 时发 Release |
| `docs/` | 英文 README 与图标 |
| `example/async_upload.yaml` | 旁路上传示例；App 里"新建配置"用的也是这份 YAML |

- 内核用 `-buildmode=c-shared` 编译，Kotlin 侧 `System.loadLibrary("mittgocore")` **加载到 App 进程里**，
  没有子进程、没有控制端口、没有 token 握手。
- `libmittgocore.so` 不提交仓库；`scripts/build-go-ndk.sh` 与 CI 负责产出。
- 只出 `arm64-v8a`。minSdk 33、targetSdk 36、compileSdk 37。版本号在 `app/build.gradle.kts` 的
  `versionName` / `versionCode`。

本仓库是**公开仓库**：不要提交本机路径、真实部署的机器/网络信息、第三方服务名、token 或凭据。示例统一用
`example.com` / `example.invalid` 和 `127.0.0.1`。游戏资源等版权内容也不要提交。

## 目录结构

```text
app/
  src/main/java/com/phantasmic/mitt/go/   页面、状态、JNI 门面
  src/main/assets/ca/                     固定 MITM CA（ca.crt / ca_key.pem）
  src/main/assets/module/                 KernelSU 模块模板（装系统证书用）
  src/main/assets/async_upload_example.yaml   新建旁路上传配置的种子
  src/main/jniLibs/                       产物目录；libmittgocore.so 不进仓库
core/
  cmd/mittgod/                            c-shared 入口 main.go + JNI 桥 jni_bridge.c
  emudhra/                                印度证书模式用的中间 CA
  server.go                               监听、透明入口、生命周期
  recorder.go                             记录库、域名过滤、旁路上传挂点
  asyncupload.go                          旁路上传：YAML 解析、匹配、POST
  upstream.go                             可选的出站上游代理
  traffic.go                              抓包通知用的流量/成功失败计数
  model.go                                配置与记录的数据模型
scripts/build-go-ndk.sh                   用 NDK 编内核
.github/workflows/build.yml               CI：内核 -> release APK -> 打 tag 发 Release
.github/ISSUE_TEMPLATE/                   issue 模板
docs/                                     英文 README、图标
example/async_upload.yaml                 旁路上传示例
```

## 一条流量怎么走

```text
App 流量 -> VpnService(TUN) -> app/.../vpn/** 组装 TCP 流
        -> 看起来像 HTTP(S) 的流：先写一行 "MITT-GO1 <ip> <port> [uid] [package]"
           再把原始字节转发给内核的透明入口
        -> Go：透明入口转成 CONNECT 打到本地代理，TLS MITM，走 go-mitmproxy 回调
        -> recorder 记录这条 flow，顺带跑域名过滤和旁路上传匹配
        -> Kotlin 每 300ms 拉一次增量，刷新抓包列表
```

- 内核就在 App 进程里：Go 侧 panic 会连 App 一起带崩。改 `core/` 时注意 nil 和并发。
- "仅代理"模式不建 TUN，只在本机开一个 HTTP 代理（可选再开 SOCKS5），方便别的设备/电脑指过来用。
- 记录库每边最多存 128 KiB 正文（`core/model.go`）；要调就改内核，不要把 Kotlin 侧单独调大。
  响应正文只存一份，预览和落盘都走 `BodyCodec`。
- 记录是"一次抓包会话"级别的内存数据，开了自动保存才归档到历史；日志是 4000 条的内存环形缓冲。

## JNI 门面

`GoCoreJni` 是 Kotlin 侧唯一的入口。内核返回的字符串在 C 侧分配、由桥接层释放，Kotlin 不持有 native 内存。

| 方法 | 作用 |
|---|---|
| `load()` | 懒加载 `.so`；失败原因保存在 `failureMessage()`。不要直接 `System.loadLibrary`。 |
| `start(configJson): String` | 启动内核。返回空串表示成功。 |
| `stop()` | 停止内核。 |
| `events(since, limit): String` | 增量记录，返回 `{seq, records}`。 |
| `clear()` | 清空内核侧记录库。 |
| `setSocks(enabled): String` | 运行中开关可选 SOCKS5。 |
| `setDomainFilter(config): String` | 下发域名过滤（`mode` + 白/黑名单）。 |
| `setAsyncUpload(config): String` | 整体替换旁路上传规则（`enabled` + 若干份 YAML 文本）。 |
| `setUpstream(config): String` | 运行中热更新上游代理。 |
| `asyncEvents(): String` | 取走旁路上传的日志/结果事件。 |
| `traffic(): String` | 累计计数：上下行字节数 + MITM 成功/失败条数。 |

加一个 JNI 方法必须同时改 `core/cmd/mittgod/main.go`、`core/cmd/mittgod/jni_bridge.c` 和
`GoCoreJni.kt`，并且先重编 `.so` 再打 APK。

## 内核 JSON 契约

### `start` 入参

```json
{
  "httpPort": 9099,
  "tunnelPort": 9098,
  "socksPort": 1080,
  "socksEnabled": false,
  "caDir": "<app files dir>/mitm-ca",
  "caCertPem": "-----BEGIN CERTIFICATE-----…",
  "caKeyPem": "-----BEGIN PRIVATE KEY-----…",
  "domainMode": "OFF|WHITE|BLACK",
  "domainWhite": ["*.example.com"],
  "domainBlack": [],
  "certMode": "BUILTIN|INDIA",
  "upstreamEnabled": false,
  "upstreamHost": "127.0.0.1",
  "upstreamPort": 7890,
  "upstreamUser": "",
  "upstreamPassword": ""
}
```

`caDir` 只是签出证书的缓存目录，CA 本体以 PEM 直传。`certMode = INDIA` 时忽略
`caCertPem`/`caKeyPem`，改用系统里的 eMudhra 中间 CA 现场签叶证书。

### 记录（`events.records[]`）

```json
{
  "id": "3f2a…",
  "uid": 10234,
  "packageName": "com.example.app",
  "startedAt": 1730000000123,
  "durationMs": 41,
  "method": "GET",
  "url": "https://example.com/api/x",
  "proto": "HTTP/1.1",
  "remoteAddr": "1.2.3.4:443",
  "statusCode": 200,
  "requestHeader":  { "Accept": ["*/*"] },
  "responseHeader": { "Content-Type": ["application/json"] },
  "requestBody":  { "size": 0, "truncated": false, "encoding": "text", "data": "" },
  "responseBody": { "size": 913, "truncated": false, "encoding": "text", "data": "…" },
  "error": ""
}
```

`header` 是 `name -> string[]`；`body.data` 已经按 `encoding` 解成 `text` / `base64`；`truncated` 表示
正文在内核上限处被截断。

### 旁路上传事件（`asyncEvents`）

```json
{ "items": [
  { "kind": "log", "level": "info", "text": "「配置名」尝试上传 https://…（响应体 1024 字节）", "time": 1730000000123 },
  { "kind": "result", "level": "info", "name": "配置名", "detail": "200 上传成功", "notify": true,
    "text": "[旁路上传] 配置名\n200 上传成功\n12345", "time": 1730000000125 }
] }
```

`kind = log` 直接进日志页。`kind = result` 是一次上传的最终结果：`text` 是通知正文，`name` / `detail` /
`notify` 用来拼那一行合并日志、并决定要不要发系统通知；`level = error` 表示失败。

### 吞吐计数（`traffic`）

```json
{ "up": 102400, "down": 819200, "ok": 32, "fail": 1 }
```

`up` / `down` 是隧道（和 SOCKS）连接上的累计字节数；`ok` 是"请求头已成功从客户端解密并落库"的请求数，
`fail` 是 `RequestError` 加失败的 CONNECT 隧道（比如客户端不认 MITM 证书）的数量。四个数都在内核
`Start` 时清零，也就是"一次抓包会话"的口径。

## 域名过滤

- `mode` 是 `OFF` / `WHITE` / `BLACK`；白名单为空等价于全放行。
- `matchDomain` 支持 `*.suffix.com` 这种通配后缀。
- TUN 的 CONNECT 只有 IP、拿不到域名，所以 `shouldIntercept` 阶段对 IP 一律放行（fail-open），真正判定在
  recorder 回调里用 `flowHost(f)` 取 host 再做。
- 设置一变（包括关掉）必须重新下发一次，否则内核里留的是旧规则。

## 旁路上传

App 把 YAML 读成字符串一次性下发给内核：`{ "enabled": true, "configs": ["<yaml>", …] }`，一份配置一个字符串。
启用状态由 App 内的开关决定，YAML 顶层的 `enable` 已废弃。

行为要点：

- `match_url` 用 Go 正则；命名捕获组会变成变量，可在 `server_url`、`rename`、`headers`、`notify.extra_info`
  里用。内置变量：`{time}`（东八区 `yyyyMMdd-HHmmss`）、`{original_url}`（命中的原始 URL）、
  `{random}`（9 位小写十六进制随机串）。
- `file.type` 决定搬请求体还是响应体。原流量照常转发，拷贝出来的 body 交给 goroutine 异步 POST。
- 上传是**整块发一次**，没有内置分片；需要 `X-Chunk-Index` 这类头就自己写进 `upload.headers`。
- `file.rename` 非空时发 `multipart/form-data`（字段名 `file`），并补 `X-Filename` / `X-File-Name`；
  留空则发裸 body。
- `file.size_limit.min` / `max`：0 表示那一端不限；命中 URL 但大小不符会写一条日志并继续试下一条规则。
- 同一个 URL 可以被多份配置同时命中（各自独立上传），但一份配置对同一个 URL 只传一次。
- `max_retries` 会在失败后重试；到上限时通知里会带上重试次数上限。
- 日志页只留这几类行（模块分组，见 `MittLog.GROUP_MODULE`）：
  - `已载入「配置名」：N 条 URL 规则 → server_url`
  - `「配置名」尝试上传 <原始 URL>（响应体 N 字节）`
  - `「配置名」过滤 <原始 URL>（响应体 N 字节）`（WARNING，命中大小过滤）
  - 重试中的失败：`「配置名」上传失败 →403 detail（重试 1/3 次）`（WARNING）
  - 最终结果和通知合并成一行：`「配置名」上传成功 通知成功→200 detail`（INFO）或
    `… 上传失败 通知失败→403 detail`（ERROR）。`通知成功` / `通知失败` 表示系统通知有没有真的发出去。
- 大于 2 MB 的流式响应没有完整 body，命中的话会传空内容（已知限制）。

## 证书模式

- `BUILTIN`：用 `app/src/main/assets/ca/` 里的固定 CA，装进系统证书后名为 `7dd0b8dd.0`（导出脚本面向
  Android 13 及以下，KernelSU 模块面向 14 及以上）。
- `INDIA`：用系统里的 eMudhra 中间 CA（`EM DV TLS CA - G3B`，P-384）现场签叶证书；它的私钥标量只有 8 位，
  `recoverWeakKey` 穷举出 `d`，链是"叶 + 中间证书"。这个模式不保证可用：设备一旦收到该 CA 的黑名单就失效，
  pinning 的 App 也一律失败。

### CA 私钥是公开的

`app/src/main/assets/ca/ca_key.pem` 随源码一起公开——这是 MITM 的前提（同一把 key 必须能解密它签出来的流量），
但也意味着拿到仓库的人可以签发被 Mitt-go 信任的站点证书。**不要在这 App 之外复用它，也不要当作通用测试 CA 分发。**

## 抓包通知与控制中心磁贴

- 抓包中 `ProxyVpnService` 以前台服务运行，常驻一条单行通知：

  ```text
  Mitt-go
  1.24 MB/s↑  86.0 KB/s↓  |  32 成功  1 失败          [停止抓包]
  ```

  每秒刷新一次：前两个数来自 `traffic()` 的差值（吞吐），后两个是内核的 MITM 计数。点通知进 App，
  `停止抓包` 停服务；停止抓包后通知立刻消失。
- `CaptureTileService` 是控制中心磁贴。图标固定是运行时用系统 emoji 字体画出来的西兰花（系统会给磁贴图标
  做单色渲染，所以只认形状）；字样跟状态走：空闲是 `Mitt-go`，抓包中是 `VPN` 或 `仅代理`。
- 空闲时点磁贴：已经授权过 VPN 就直接 `startForegroundService` 起服务，否则打开 App 让系统弹授权；
  抓包中点磁贴会发 `com.phantasmic.mitt.go.STOP_CAPTURE` 广播，由正在跑的服务按通知按钮同一条路径处理。

## 检查更新

唯一实现在 `Updater.kt`：版本号来自 GitHub `releases/latest` 的 302 跳转地址
（`/releases/tag/vX.Y.Z`），与 `BuildConfig.VERSION_NAME` 按数字段比较；15 秒没有响应算超时，提示重试。
安装包下载到 `cacheDir/updates/`，完成后用 FileProvider + `ACTION_VIEW` 拉起系统安装器，起不来时退回
`Download/Mitt-go` 让用户手动装。`REQUEST_INSTALL_PACKAGES` 权限、`res/xml/file_paths.xml` 和 manifest
里的 provider 都是这个功能加的。

## Kotlin 侧关键单例

| 位置 | 职责 |
|---|---|
| `ProxyCore` | JNI 之上的语义层：解析 JSON、合并历史、`historyCache`（最近 40 条详情） |
| `ProxyRuntime` | 抓包状态、端口、与 `ProxyVpnService` 协作 |
| `ProxyVpnService` | TUN 生命周期、前台通知、吞吐 ticker、`AsyncUploadRuntime.sync()` |
| `CaptureStore` | 记录列表、300ms 增量轮询、已读集合、历史文件 |
| `AsyncUploadStore` | 旁路上传的配置文件、revision 信号、YAML 文本、导出 |
| `AsyncUploadRuntime` | 把开关 + 全部 YAML 下发给内核，并 `drain()` 事件（日志 + 通知） |
| `CaptureTileService` | 控制中心磁贴：停止走广播，开启走前台服务或打开 App |
| `AppSettings` | 所有落盘开关（证书模式、旁路上传、域名过滤、上游代理等） |
| `ui/Glass.kt` | 唯一的玻璃实现：层级、参数、取景图层、`GlassCard`、`CircleGlassButton` |
| `ui/MittOverlay.kt` | 浮层：`MittModal`、`GlassPopover`、`GlassMenuItem`、动效参数 |
| `ui/PageChrome.kt` | 一级页的悬浮元素容器，按 Pager 偏移跟着页面平移 |

`MittGoRoot` 里是自建导航栈：`NavEntry`（`Settings` / `Domain` / `History` / `Detail` / `AsyncUpload`）+
`NavLayer` 列表 + `Animatable` 驱动的 `translationX`（进 320ms、退 280ms）。加二级页就是加一个 `NavEntry`
分支和对应的 `when`。

## 约定

**同一个能力只允许一个实现**，不要另造第二份：

- 记录：`CaptureStore`；记录模型辅助：`CaptureModel.kt`；编解码：`CaptureCodec`。
- 引擎启停与配置：`ProxyCore`；运行状态：`ProxyRuntime`。
- 正文预览与落盘：`BodyCodec.render()` / `BodyCodec.export()`——保存出来的 JSON 和预览一样是缩进版。
- 存文件：`DownloadStore.save()`；后台导出 + Toast：`exportAsync()`。
- 体积/时间：`formatSize()`、`TimeFmt`。图标：`Glyphs.kt`。
- 检查更新：`Updater`。浮层：`MittOverlay`。一级页悬浮元素：`PageChrome`。
- 设置：先落盘到 `AppSettings`，再走 `SettingsRuntime.apply(context, previous, updated)`。

**玻璃与浮层：**

- `ui/Glass.kt` 是唯一的玻璃实现。页面自己建取景图层（`rememberPageBackdrop()` +
  `Modifier.pageBackdrop()`），页面里的玻璃必须画在记录节点的**兄弟位置**；画在记录节点内部会让 hwui 的
  RenderNode 树成环，`prepareTreeImpl` 递归到栈溢出（SIGSEGV）。抓包页和旁路上传页为页面内浮层留了独立的
  "列表取景图层"（`listBackdrop`），按那套写法来，不要退回页面级 backdrop，也不要在静态页面常驻全屏取景。
- 弹窗/菜单一律走 `MittModal` / `GlassPopover`：遮罩、进出场、返回键都在里面。不要在页面里为浮层自己加
  `BackHandler`。
- 下拉回弹是全 App 统一的：`MiuixTheme` 通过 `LocalOverscrollFactory` 装了 `MiuixOverscrollFactory`，
  不要在页面里自己写 `NestedScrollConnection`。
- 深浅色判断用 `GlassDefaults.isDark()`（读解析后的 surface 亮度），不要用 `isSystemInDarkTheme()`——
  App 支持强制浅色/深色。

**编辑器：**

- YAML 编辑器的高亮绝不能改字符数（`OffsetMapping` 要求前后长度一致；不要用 `String.lines()`，它会吃掉
  `\r`）。回车会自动续当前行的缩进（`autoIndentAfterEnter`）。

**启动与性能：**

- 启动衔接：页面第一帧就完全不透明，靠系统启动画面那 300ms 淡出"揭开"，两个动画并行；不要给内容套
  `graphicsLayer { alpha }` 淡入（会和系统动画串行，中间露出一段空档）。底栏第一帧就组合，不要延迟弹出。
- 不要加常驻的无限动画：抓包 FAB 的呼吸环只在抓包中按 `withFrameNanos`（约 60fps）推进，停止后一帧都不跑。
- 轮询：抓包中 300ms、空闲 1s；抓包列表发布最多 400ms 一次，不要在轮询里直接刷 UI。
- 关于页背景是整屏 AGSL shader，按 30fps 用 `withFrameNanos` 重绘，不要改回逐帧。
- VPN 收包路径复用 `SocketChannelReader.readBuffer`，不要每个 selector 事件 new 64 KiB。
- 内核启动时设了 `GOGC=200` + 192 MiB 软上限，减少抓包时的 GC 次数；调之前先量。
- 内核正文上限每边 128 KiB（`core/model.go` 的 `defaultMaxBody`），Kotlin 侧最多拉这么多，不要只调一边。

## 构建

需要 JDK 17、带 `compileSdk 37` 的 Android SDK、Go 1.23+、Android NDK 27.2。

```bash
export ANDROID_NDK_HOME=<ndk 路径>

./scripts/build-go-ndk.sh          # 内核 -> app/src/main/jniLibs/arm64-v8a/libmittgocore.so
gradle :app:assembleDebug
gradle :app:assembleRelease        # R8 + 资源压缩
```

只要改了 JNI 方法，内核和 Kotlin 必须一起重编。

## 测试

- `cd core && go test ./`：离线单测，覆盖规则编译、旁路上传请求、上游代理 URL 拼接、吞吐计数。
- 没有 Android 单测/仪器测试。UI 改动请在真机上验证：装 debug APK，依次检查抓包开始/停止（App、磁贴、
  通知三条路径）、旁路上传、导出、历史。
- CI 每次 push 都会编内核并产出 release APK。

## Git 提交

所有提交标题格式：

```text
[Type] Short description starting with a capital letter
```

| Type | 用途 |
|---|---|
| `[Feat]` | 新功能或能力 |
| `[Fix]` | Bug 修复 |
| `[Perf]` | 性能优化 |
| `[Chore]` | 维护、重构、依赖、构建或 CI 改动 |
| `[Docs]` | 只改文档 |

- 祈使句、首字母大写、不加句号，标题控制在 70 字符左右。
- 直接提交到 `main`，靠 push 合并；保持 `main` 是绿的，不要改写已发布的历史。
- Agent 协作统一用标准的 `Co-authored-by:` trailer：放在提交正文里，和标题之间空一行
  （例如 `Co-authored-by: <model> <noreply@example.invalid>`）。
- 版本号和改动放在同一个提交里，改 `app/build.gradle.kts` 的 `versionCode` / `versionName`：
  `versionCode` 永远 +1；`versionName` 对 `[Feat]` 升 minor，对 `[Fix]` / `[Perf]` 升 patch。
  一批改动升一次就够。
- 永远不要提交别人的部署 CA 私钥、token、keystore 或签名口令。

## GitHub Actions

`.github/workflows/build.yml` 是唯一的 workflow。

- push / PR：装 JDK 17 和 Go，Android SDK 37 + build-tools 35 + NDK 27.2，跑
  `scripts/build-go-ndk.sh` 和 `:app:assembleRelease`（R8 + 资源压缩），上传 `Mitt-go-release-apk`
  artifact，里面有 `Mitt-go-arm64-v8a-release-<version>.apk`。
- `v*` tag：额外把这个 APK 挂到 GitHub Release。tag 必须和 `versionName` 对齐（`vX.Y.Z`），
  `versionCode` 必须严格递增。
- release 步骤需要 `contents: write`，不要给更宽的权限；新增第三方 action 时固定到完整 commit SHA。
- 签名：release job 从仓库 secrets 解出 keystore，没有配置时退回 debug key。keystore、别名和口令都不进仓库。

## 发布

- Release 标题就是 tag（`vX.Y.Z`）。
- Release 说明用英文列出用户可见的变化：功能、修复、性能。不要写部署细节、内部服务名和凭据。
- APK 资产文件名：`Mitt-go-arm64-v8a-release-<version>.apk`。
- `debug` 和 `release` 用同一把签名 key，可以互相覆盖安装；不要发别的 key 签出来的包，否则用户必须先卸载。

## 容易踩的坑

- Go 内核 panic 会直接把 App 进程带崩。注意 nil flow 和并发访问。
- JNI 符号名不能被混淆：`GoCoreJni` 的 `external` 方法在 `proguard-rules.pro` 里必须 keep，
  少了就是启动时 `UnsatisfiedLinkError`。
- 16 KB 页对齐：`.so` 链接要带 `-Wl,-z,max-page-size=16384`，并保留
  `packaging { jniLibs { useLegacyPackaging = true } }`，否则 Android 15+ 设备可能装不上或映射失败。
- 玻璃画进记录节点会闪退（RenderNode 成环），见「约定」。
- Android 14+ 的 VPN 服务必须以前台服务启动，类型是 `specialUse`；manifest 里声明了
  `FOREGROUND_SERVICE_SPECIAL_USE` 和 special-use 子类型属性。
- 抓包通知 channel 是 `IMPORTANCE_LOW`，别调成会响的类型。
- Miuix 的 `ButtonDefaults.buttonColors` 参数名是 `color`，不是 `containerColor`。
