# Mitt-go 开发者说明（仓库版）

这份文档面向「拿到仓库、想接着改」的人，讲清楚当前进度、代码怎么分层、内核 API 怎么调。

## 1. 现状

- 版本 `2.0.1`（versionCode 76），minSdk 33 / targetSdk 36 / compileSdk 37，仅 `arm64-v8a`
- 内核：Go 1.23 + `go-mitmproxy v1.9.4`，编成 `libmittgocore.so`（`-buildmode=c-shared`），JNI 直调
- 平台侧：Kotlin + Jetpack Compose 1.12.0 + Miuix 0.9.4

已经做完的：导航栈与转场、四项一级页 Pager、抓包列表/详情/导出、已读变暗、搜索与高亮、按 URL/域名两种视图、域名过滤（白/黑名单）、历史落盘、证书页（内置 + 印度两模式、三种导出方式）、旁路上传（YAML 导入、编辑器、正则命中、异步 POST、大小限制、重试、通知）。

## 2. 目录

```
app/                     Android 壳（Kotlin + Compose + Miuix）
  src/main/java/.../go/  所有页面、状态、JNI 门面
  src/main/assets/ca/    固定 CA（ca.crt / ca_key.pem）
  src/main/assets/module/ KernelSU 模块模板
  src/main/jniLibs/      产物目录，libmittgocore.so 不放仓库
core/                    Go 内核
  cmd/mittgod/           c-shared 入口 main.go + JNI 桥 jni_bridge.c
  emudhra/               印度模式用的中间 CA 证书
  recorder.go            记录、域名过滤、旁路上传挂点
  asyncupload.go         旁路上传
scripts/build-go-ndk.sh 用 NDK 编内核
docs/                    英文说明
example/                 旁路上传配置示例
```

## 3. 数据流

```
App 流量 → VpnService(TUN) → app/.../vpn/** 组装 TCP 流
        → 若像 HTTP(S)：写一行 "MITT-GO1 <ip> <port>\n" 再转发给内核透明入口
        → Go：透明入口转成 CONNECT，MITM 解密，go-mitmproxy 回调
        → recorder 把请求/响应塞进内存表，顺带跑域名过滤和旁路上传匹配
        → Kotlin 每 300ms 拉一次增量（JNI events），更新列表
```

内核在 App 进程里（`System.loadLibrary("mittgocore")`），Go panic 会直接带崩进程，改 Go 侧代码时要格外小心 nil 与并发。

## 4. JNI 门面

Kotlin 侧只有一个入口 `GoCoreJni`（`GoCoreJni.kt`），全部是 `external fun`：

| 方法 | 说明 |
| --- | --- |
| `load()` | 懒加载 `.so`，失败原因保留在 `failureMessage()`，不要直接 `System.loadLibrary` |
| `start(coreJson: String): String` | 启动内核，返回空串表示成功 |
| `stop()` | 停止内核 |
| `snapshot(): String` | 全量快照（运行状态、端口、最近记录摘要） |
| `record(id): String` | 单条完整记录，找不到返回空对象 |
| `events(since: Long, limit: Int): String` | 增量变更，返回 `{seq, records}` |
| `clear()` | 清空内核里的记录 |
| `dump(limit, maxBody): String` | 导出完整记录（用于落盘历史），`maxBody` 是每条 body 的上限字节 |
| `setSocks(enabled): String` | 运行中开关 SOCKS5 |
| `setDomainFilter(config): String` | 下发域名过滤规则 |
| `setAsyncUpload(config): String` | 下发旁路上传配置 |
| `asyncEvents(): String` | 取走内核攒下的旁路上传事件 |

约定：所有返回 `String` 的方法，成功返回 `""`（或 JSON 文本），失败返回错误消息；C 侧字符串由 `MittGoFree` 释放，桥接层已经处理好，Kotlin 侧不用管。

## 5. 内核要知道的三个 JSON

### 5.1 `start` 的入参

```json
{
  "httpPort": 9099,
  "tunnelPort": 9098,
  "socksPort": 1080,
  "socksEnabled": false,
  "caDir": "/data/user/0/com.phantasmic.mitt.go/files/mitm-ca",
  "caCertPem": "-----BEGIN CERTIFICATE-----…",
  "caKeyPem": "-----BEGIN PRIVATE KEY-----…",
  "domainMode": "OFF|WHITE|BLACK",
  "domainWhite": ["*.example.com"],
  "domainBlack": [],
  "certMode": "BUILTIN|INDIA"
}
```

CA 不落成固定文件也能跑：`caDir` 只是内核缓存签发结果的地方，证书本体从 assets 读出来直传。
`certMode = INDIA` 时忽略 `caCertPem/caKeyPem`，改用系统里的 eMudhra 中间 CA 现场签叶证书。

### 5.2 `record` / `events.records[]` / `dump.records[]` 的结构

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

`header` 是 `name → string[]`，`body.data` 已经按 `encoding` 处理过（`text` / `base64`），`truncated` 表示被内核截断。

### 5.3 `asyncEvents` 的结构

```json
{ "items": [ { "kind": "upload", "level": "info", "title": "Mitt-go", "text": "[旁路上传] 配置名\n200 上传成功\n12345", "time": 1730000000123 } ] }
```

Kotlin 侧 `ProxyCore.asyncEvents()` 把它转成 `AsyncEvent`，`AsyncUploadRuntime.drain()` 负责取走并弹通知。

## 6. 域名过滤

- `mode`：`OFF` / `WHITE` / `BLACK`；白名单为空时等价于全放行
- `matchDomain` 支持 `*.suffix.com` 这种通配后缀
- **重要**：TUN 的 CONNECT 目标只有 IP，拿不到域名，所以过滤在 `shouldIntercept` 阶段对 IP 一律放行（fail-open），真正的判定发生在 recorder 的 `Request/Response/RequestError` 回调里，用 `flowHost(f)` 取 host 再判断
- 修改设置后必须再调一次 `setDomainFilter`（哪怕关掉），否则内核里留着旧规则

## 7. 旁路上传

YAML 由 Kotlin 读成字符串，一次性打包下发：

```json
{ "enabled": true, "configs": ["<yaml 文本>", "<yaml 文本>"] }
```

行为要点：

- 匹配用 `match_url` 里的 Go 正则（支持命名捕获组），变量可在 `server_url` / `rename` / `notify.extra_info` 里用；内置 `{time}` 固定东八区 `yyyyMMdd-HHmmss`
- `file.type` 决定搬请求体还是响应体；原流量照常转发，拷贝出来的 body 交给 goroutine 异步 POST
- `file.rename` 非空时发 `multipart/form-data`（字段名 `file`），同时补 `X-Filename` / `X-File-Name` 头；为空则发裸 body
- `size_limit.min/max`：0 表示该端不限；命中 URL 但大小不符会写一条「跳过」日志并继续试下一条规则
- `max_retries`：失败重试到上限后，通知里追加「重试次数达到上限」
- 通知：标题 `Mitt-go`，正文三行（`[旁路上传] 配置名` / 状态码 + 从 JSON 里抽的 `detail|message` / `extra_info`），用 `setWhen(timestamp)` 让系统显示相对时间
- 大于 2 MB 的流式响应没有完整 body，此时会上传空内容（已知限制）

## 8. 证书

- `BUILTIN`：用 `assets/ca` 的固定 CA，系统证书名 `7dd0b8dd.0`；导出脚本面向 Android 13 及以下，KernelSU 模块面向 14 及以上
- `INDIA`：`core/certmode_india.go` 用 `em_dv_tls_ca_g3b_aaa.pem`（`EM DV TLS CA - G3B`，P-384）作为签发者，私钥标量只有 8 位，`recoverWeakKey` 穷举出 `d` 后签 P-256 叶证书，链是「叶 + 中间证书」
- 印度模式不保证成功：设备一旦通过 Play services 收到该 CA 的黑名单就失效；pinning 的 App 也一律失败

### 仓库里的 CA 私钥

`app/src/main/assets/ca/ca_key.pem` 是随源码一起公开的固定 CA 私钥。它是 MITM 的前提——同一把 key 必须能解密自己签出来的流量——但也意味着拿到这个仓库的人可以签发被 Mitt-go 信任的站点证书。**不要在生产环境复用这把 key，也不要把它当作通用测试 CA 分发。**

## 9. Kotlin 侧关键单例

| 位置 | 职责 |
| --- | --- |
| `ProxyCore` | JNI 门面之上的语义层：解析 JSON、合并历史、`historyCache`（最近 40 条详情） |
| `ProxyRuntime` | 抓包启停、端口、与 `ProxyVpnService` 协作 |
| `ProxyVpnService` | TUN 生命周期；内核起来后调 `AsyncUploadRuntime.sync()` |
| `CaptureStore` | 记录列表、增量轮询（300ms）、已读集合（`visitedFlow`，上限 1000 条，存 SharedPreferences `mitt_go_visited`） |
| `AsyncUploadStore` | 配置文件读写、`revision` 变更信号、YAML 文本、导出 |
| `AsyncUploadRuntime` | 把开关 + 全部 YAML 下发给内核，并 `drain()` 事件弹通知 |
| `AppSettings` | 所有开关的持久化（含 `certMode`、`asyncUploadEnabled`、域名过滤规则） |

`MittGoRoot` 里的导航栈是自建的：`NavEntry`（`Settings` / `Domain` / `History` / `Detail` / `AsyncUpload`）+ `NavLayer` 列表 + `Animatable` 驱动 `translationX`，进入 320ms、退出 280ms。新增二级页时加一个 `NavEntry` 分支和对应的 `when` 就够。

## 10. 构建与 CI

先 `export ANDROID_NDK_HOME=<ndk 路径>`，然后：

```bash
./scripts/build-go-ndk.sh        # 编内核
gradle :app:assembleDebug
gradle :app:assembleRelease      # R8 + shrinkResources
```

CI（`.github/workflows/build.yml`）走同一套：checkout → JDK 17 → Android SDK/NDK → Go → `build-go-ndk.sh` → `assembleRelease` → 上传 APK；打 tag 时顺便发 Release。

## 11. 容易踩的坑

1. **JNI 名字不能被混淆**。`GoCoreJni` 的 `external` 方法名就是 C 侧符号名，`proguard-rules.pro` 里必须 keep，否则 Release 包一启动就 `UnsatisfiedLinkError`。
2. **16 KB 页对齐**。`.so` 链接时要 `-Wl,-z,max-page-size=16384`，并且 `packaging { jniLibs { useLegacyPackaging = true } }`，否则 Android 15+ 设备上可能装不上或映射失败。
3. **Compose 里的液态玻璃 backdrop 不能成环**。搜索框/浮层要挂在页面内部的 `layerBackdrop` 上，直接传页面级 backdrop 会导致渲染树递归，最终 `RenderNode::prepareTreeImpl` 栈溢出闪退。
4. **YAML 编辑器的高亮绝不能改字符数**。`OffsetMapping` 要求变换前后长度一致，`String.lines()` 会吃掉 `\r`，必须自己按 `\n` 扫描；长度不一致就直接放弃高亮。
5. **Miuix 的 `ButtonDefaults.buttonColors` 参数名是 `color`**，不是 `containerColor`。

## 12. 后续可做

功能层面还能往下走的几处：

- [ ] 旁路上传支持超 2 MB 的流式响应（当前命中的话会传空文件）
- [ ] 模块页目前只有旁路上传，其它扩展点还空着
- [ ] 详情页正文的搜索/复制体验可以再打磨
