package mittgo

// 旁路上传（async upload）
//
// 命中的 URL 不会改变原来的流量：请求/响应照常转发给 App，只是额外把
// 请求体或响应体异步 POST 到用户配置的服务器，并在完成后给 Android 侧
// 推一条通知事件。
//
// 配置来源是 YAML（见 example.yaml），由 Kotlin 侧读成字符串后一次性下发：
//
//	{"enabled":true,"configs":["<yaml 文本>","<yaml 文本>"]}
//
// 这里不碰文件系统，也不依赖 Android API，保持库可以在纯 Go 里跑。

import (
	"bytes"
	"crypto/rand"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"gopkg.in/yaml.v3"

	"github.com/lqqyt2423/go-mitmproxy/proxy"
)

// ---------------- YAML 结构 ----------------

type asyncYamlConfig struct {
	Enable   bool     `yaml:"enable"`
	Name     string   `yaml:"name"`
	MatchURL []string `yaml:"match_url"`
	Upload   struct {
		ServerURL     string              `yaml:"server_url"`
		TLSSkipVerify bool                `yaml:"tls_skip_verify"`
		Headers       []map[string]string `yaml:"headers"`
		File          struct {
			Type   string `yaml:"type"`
			Rename string `yaml:"rename"`
			// 只有大小落在 [min,max] 里的文件才上传；0 表示不限。
			SizeLimit struct {
				Min int `yaml:"min"`
				Max int `yaml:"max"`
			} `yaml:"size_limit"`
		} `yaml:"file"`
		Timeout    int `yaml:"timeout"`
		MaxRetries int `yaml:"max_retries"`
	} `yaml:"upload"`
	Notify struct {
		Enabled   bool `yaml:"enabled"`
		ExtraInfo any  `yaml:"extra_info"`
	} `yaml:"notify"`
}

type asyncRule struct {
	name        string
	patterns    []*regexp.Regexp
	serverURL   string
	skipVerify  bool
	headers     []map[string]string
	fromRequest bool
	rename      string
	minSize     int
	maxSize     int
	timeout     time.Duration
	maxRetries  int
	notify      bool
	extraInfo   string
}

type asyncSet struct {
	enabled bool
	rules   []asyncRule
}

var asyncStore atomic.Value

func init() {
	asyncStore.Store(&asyncSet{})
}

// ---------------- 通知 / 日志队列 ----------------

type asyncNotice struct {
	Seq    int64  `json:"seq"`
	Time   int64  `json:"time"`
	Kind   string `json:"kind"`  // log = 只写日志页，result = 上传最终结果（日志页 + 可选系统通知）
	Level  string `json:"level"` // info / warn / error
	Title  string `json:"title"`
	Text   string `json:"text"`
	Name   string `json:"name,omitempty"`   // 配置名（result 拼日志行用）
	Detail string `json:"detail,omitempty"` // 状态码 + 服务器返回的那句（和通知第二行一致）
	Notify bool   `json:"notify,omitempty"` // result 要不要弹系统通知
}

var asyncQueue struct {
	mu    sync.Mutex
	items []asyncNotice
	seq   int64
}

const asyncQueueLimit = 400

func asyncPush(kind, level, title, text string) {
	asyncEnqueue(asyncNotice{Kind: kind, Level: level, Title: title, Text: text})
}

// asyncPushResult 上传最终结果：日志行和系统通知都由 Android 侧从这条事件拼出来，
// 这样「通知有没有发出去」能写进同一行日志。
func asyncPushResult(level, name, detail, body string, notify bool) {
	body = strings.TrimSpace(body)
	if body == "" {
		return
	}
	fmt.Fprintf(os.Stderr, "mittgo: async: result %s\n", strings.ReplaceAll(body, "\n", " "))
	asyncEnqueue(asyncNotice{
		Kind:   "result",
		Level:  level,
		Title:  "Mitt-go",
		Text:   body,
		Name:   name,
		Detail: strings.TrimSpace(detail),
		Notify: notify,
	})
}

func asyncEnqueue(item asyncNotice) {
	item.Text = strings.TrimSpace(item.Text)
	if item.Text == "" {
		return
	}
	asyncQueue.mu.Lock()
	asyncQueue.seq++
	item.Seq = asyncQueue.seq
	item.Time = time.Now().UnixMilli()
	asyncQueue.items = append(asyncQueue.items, item)
	if len(asyncQueue.items) > asyncQueueLimit {
		asyncQueue.items = asyncQueue.items[len(asyncQueue.items)-asyncQueueLimit:]
	}
	asyncQueue.mu.Unlock()
}

// asyncLogf 同时写 stderr（开发期看得到）和日志页。
func asyncLogf(format string, args ...any) {
	text := fmt.Sprintf(format, args...)
	fmt.Fprintf(os.Stderr, "mittgo: async: %s\n", text)
	asyncPush("log", "info", "", text)
}

func asyncWarnf(format string, args ...any) {
	text := fmt.Sprintf(format, args...)
	fmt.Fprintf(os.Stderr, "mittgo: async: %s\n", text)
	asyncPush("log", "warn", "", text)
}

// AsyncEvents 由 Kotlin 侧轮询取走；取走即清空，避免重复弹通知。
func AsyncEvents() string {
	asyncQueue.mu.Lock()
	items := asyncQueue.items
	asyncQueue.items = nil
	asyncQueue.mu.Unlock()
	if items == nil {
		items = []asyncNotice{}
	}
	payload, err := json.Marshal(map[string]any{"items": items})
	if err != nil {
		return "{\"items\":[]}"
	}
	return string(payload)
}

// ---------------- 配置下发 ----------------

type asyncPayload struct {
	Enabled bool     `json:"enabled"`
	Configs []string `json:"configs"`
}

// SetAsyncUpload 替换整套规则。返回空串表示成功，否则是错误描述。
func SetAsyncUpload(payloadJSON string) string {
	var payload asyncPayload
	if err := json.Unmarshal([]byte(payloadJSON), &payload); err != nil {
		return "decode async upload config: " + err.Error()
	}
	set := &asyncSet{enabled: payload.Enabled}
	if payload.Enabled {
		for index, raw := range payload.Configs {
			var cfg asyncYamlConfig
			if err := yaml.Unmarshal([]byte(raw), &cfg); err != nil {
				asyncWarnf("第 %d 份配置解析失败：%v", index+1, err)
				continue
			}
			rule, err := compileAsyncRule(cfg)
			if err != nil {
				asyncWarnf("配置「%s」无效：%v", cfg.Name, err)
				continue
			}
			set.rules = append(set.rules, rule)
			asyncLogf("已载入「%s」：%d 条 URL 规则 → %s", rule.name, len(rule.patterns), rule.serverURL)
		}
	}
	asyncStore.Store(set)
	return ""
}

func compileAsyncRule(cfg asyncYamlConfig) (asyncRule, error) {
	name := strings.TrimSpace(cfg.Name)
	if name == "" {
		name = "未命名"
	}
	serverURL := strings.TrimSpace(cfg.Upload.ServerURL)
	if serverURL == "" {
		return asyncRule{}, fmt.Errorf("upload.server_url 不能为空")
	}
	if len(cfg.MatchURL) == 0 {
		return asyncRule{}, fmt.Errorf("match_url 至少要有一条正则")
	}
	patterns := make([]*regexp.Regexp, 0, len(cfg.MatchURL))
	for _, pattern := range cfg.MatchURL {
		pattern = strings.TrimSpace(pattern)
		if pattern == "" {
			continue
		}
		// (?<name>...) 是 Go 1.22+ 才认的写法，统一换成老式 (?P<name>...)。
		pattern = strings.ReplaceAll(pattern, "(?<", "(?P<")
		re, err := regexp.Compile(pattern)
		if err != nil {
			return asyncRule{}, fmt.Errorf("match_url %q 无法编译：%w", pattern, err)
		}
		patterns = append(patterns, re)
	}
	if len(patterns) == 0 {
		return asyncRule{}, fmt.Errorf("match_url 里没有可用的正则")
	}
	timeout := cfg.Upload.Timeout
	if timeout <= 0 {
		timeout = 10
	}
	return asyncRule{
		name:        name,
		patterns:    patterns,
		serverURL:   serverURL,
		skipVerify:  cfg.Upload.TLSSkipVerify,
		headers:     cfg.Upload.Headers,
		fromRequest: strings.Contains(strings.ToLower(cfg.Upload.File.Type), "request"),
		rename:      strings.TrimSpace(cfg.Upload.File.Rename),
		minSize:     maxInt(cfg.Upload.File.SizeLimit.Min, 0),
		maxSize:     maxInt(cfg.Upload.File.SizeLimit.Max, 0),
		timeout:     time.Duration(timeout) * time.Second,
		maxRetries:  maxInt(cfg.Upload.MaxRetries, 0),
		notify:      cfg.Notify.Enabled,
		extraInfo:   normalizeExtraInfo(cfg.Notify.ExtraInfo),
	}, nil
}

// normalizeExtraInfo 兼容 extra_info: "1234567890" 和 extra_info: {user_id} 两种写法。
func normalizeExtraInfo(value any) string {
	switch typed := value.(type) {
	case nil:
		return ""
	case string:
		return strings.TrimSpace(typed)
	case map[string]any:
		parts := make([]string, 0, len(typed))
		for key := range typed {
			parts = append(parts, "{"+key+"}")
		}
		return strings.Join(parts, " ")
	case map[any]any:
		parts := make([]string, 0, len(typed))
		for key := range typed {
			parts = append(parts, "{"+fmt.Sprint(key)+"}")
		}
		return strings.Join(parts, " ")
	default:
		return strings.TrimSpace(fmt.Sprint(typed))
	}
}

// randomToken 返回 n 位小写十六进制随机串。
func randomToken(n int) string {
	const hex = "0123456789abcdef"
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		seed := time.Now().UnixNano()
		for i := range buf {
			buf[i] = hex[byte(seed>>uint(i*4))&0x0f]
		}
		return string(buf)
	}
	for i := range buf {
		buf[i] = hex[buf[i]&0x0f]
	}
	return string(buf)
}

func maxInt(value, minimum int) int {
	if value < minimum {
		return minimum
	}
	return value
}

// ---------------- 命中与上传 ----------------

var asyncTemplate = regexp.MustCompile(`\{([A-Za-z0-9_]+)\}`)

func expandTemplate(text string, vars map[string]string) string {
	if text == "" {
		return ""
	}
	return asyncTemplate.ReplaceAllStringFunc(text, func(token string) string {
		key := token[1 : len(token)-1]
		if value, ok := vars[key]; ok {
			return value
		}
		return token
	})
}

// flowURL 拼出完整 URL（scheme://host[:port]/path?query）。
// MITM 内层请求是 origin-form，域名只在 Host 头里。
func flowURL(f *proxy.Flow) string {
	if f == nil || f.Request == nil || f.Request.URL == nil {
		return ""
	}
	u := f.Request.URL
	if u.Scheme == "http" || u.Scheme == "https" {
		return u.String()
	}
	if strings.HasPrefix(u.Path, "http://") || strings.HasPrefix(u.Path, "https://") {
		return u.Path
	}
	host := strings.TrimSpace(rawHost(f))
	if host == "" {
		host = u.Host
	}
	if host == "" {
		host = flowHost(f)
	}
	if host == "" {
		return ""
	}
	host = strings.TrimSuffix(host, ":443")
	return "https://" + host + u.RequestURI()
}

// asyncHandleFlow 在 recorder 的 Response 回调里调用：此时请求体和响应体都已在内存里。
func asyncHandleFlow(f *proxy.Flow) {
	set, _ := asyncStore.Load().(*asyncSet)
	if set == nil || !set.enabled || len(set.rules) == 0 {
		return
	}
	if f == nil || f.Request == nil || f.Response == nil {
		return
	}
	url := flowURL(f)
	if url == "" {
		return
	}
	for _, rule := range set.rules {
		for _, pattern := range rule.patterns {
			match := pattern.FindStringSubmatch(url)
			if match == nil {
				continue
			}
			vars := make(map[string]string)
			for index, name := range pattern.SubexpNames() {
				if index == 0 || name == "" || index >= len(match) {
					continue
				}
				vars[name] = match[index]
			}
			vars["time"] = beijingTime().Format("20060102-150405")
			// 内置变量：原始请求 URL，以及 9 位随机串（有些接口要求每次上传带唯一 ID）。
			vars["original_url"] = url
			vars["random"] = randomToken(9)

			var payload []byte
			if rule.fromRequest {
				payload = f.Request.Body
			} else {
				payload = f.Response.Body
			}
			if !rule.sizeAllowed(len(payload)) {
				asyncWarnf("「%s」过滤 %s（%s %d 字节）", rule.name, url, bodySource(rule.fromRequest), len(payload))
				continue
			}
			// 复制一份：回调返回后 mitmproxy 不再保证这块内存还在。
			body := append([]byte(nil), payload...)
			asyncLogf("「%s」尝试上传 %s（%s %d 字节）", rule.name, url, bodySource(rule.fromRequest), len(body))
			go rule.upload(body, vars)
			// 一份配置里只要有一个 match_url 命中就够了，跳出 pattern 循环；
			// 但不要 return —— 同一个 URL 允许被多份配置同时命中，
			// 这样一个包可以既传外部服务又传自己的服务器。
			break
		}
	}
}

// 文件名里的时间服务器会按北京时间解析，所以固定东八区，不看设备时区。
func beijingTime() time.Time {
	return time.Now().In(time.FixedZone("CST", 8*60*60))
}

// sizeAllowed：min/max 为 0 表示那一端不限。
func (r asyncRule) sizeAllowed(size int) bool {
	if r.minSize > 0 && size < r.minSize {
		return false
	}
	if r.maxSize > 0 && size > r.maxSize {
		return false
	}
	return true
}

func bodySource(fromRequest bool) string {
	if fromRequest {
		return "请求体"
	}
	return "响应体"
}

func (r asyncRule) client() *http.Client {
	transport := &http.Transport{
		Proxy:           nil,
		TLSClientConfig: &tls.Config{InsecureSkipVerify: r.skipVerify},
	}
	return &http.Client{Timeout: r.timeout, Transport: transport}
}

func (r asyncRule) upload(body []byte, vars map[string]string) {
	target := expandTemplate(r.serverURL, vars)
	filename := expandTemplate(r.rename, vars)
	client := r.client()
	attempts := r.maxRetries + 1
	var lastStatus int
	var lastMessage string
	var lastErr error
	for attempt := 1; attempt <= attempts; attempt++ {
		status, message, err := r.post(client, target, filename, body, vars)
		lastStatus, lastMessage, lastErr = status, message, err
		if err == nil && status >= 200 && status < 300 {
			r.finish(vars, "info", statusLine(status, message))
			return
		}
		// 还有下一次机会时才写 retry 行；最后一次交给 finish 出「上传失败」那行。
		if attempt < attempts {
			if err != nil {
				asyncWarnf("「%s」上传失败 →%s（重试 %d/%d 次）", r.name, shorten(err.Error(), 80), attempt, attempts)
			} else {
				asyncWarnf("「%s」上传失败 →%s（重试 %d/%d 次）", r.name, statusLine(status, message), attempt, attempts)
			}
			time.Sleep(time.Duration(attempt) * 800 * time.Millisecond)
		}
	}
	if lastErr != nil {
		r.finish(vars, "error", "上传失败 "+shorten(lastErr.Error(), 80))
		return
	}
	r.finish(vars, "error", statusLine(lastStatus, lastMessage))
}

// finish 把最终结果交给 Android 侧：一行合并日志 + （配置允许时）系统通知。
func (r asyncRule) finish(vars map[string]string, level, detail string) {
	// 通知正文第一行永远是「[旁路上传] 配置名」，方便一眼看出是哪份规则。
	lines := []string{"[旁路上传] " + r.name}
	bodyDetail := strings.TrimSpace(detail)
	if level == "error" && r.maxRetries > 0 {
		bodyDetail += "\n重试次数达到上限"
	}
	if bodyDetail != "" {
		lines = append(lines, bodyDetail)
	}
	if extra := strings.TrimSpace(expandTemplate(r.extraInfo, vars)); extra != "" {
		lines = append(lines, extra)
	}
	asyncPushResult(level, r.name, detail, strings.Join(lines, "\n"), r.notify)
}

// post 把 body 当成一个完整文件，发一次请求。
func (r asyncRule) post(client *http.Client, target, filename string, body []byte, vars map[string]string) (int, string, error) {
	// 有文件名就走 multipart/form-data：很多服务端
	// 是从 multipart 的 filename 里取名字的，裸 body 只能读到默认值。
	var reader io.Reader
	multipartType := ""
	if filename != "" {
		var buf bytes.Buffer
		writer := multipart.NewWriter(&buf)
		part, err := writer.CreateFormFile("file", filename)
		if err != nil {
			return 0, "", err
		}
		if _, err := part.Write(body); err != nil {
			return 0, "", err
		}
		if err := writer.Close(); err != nil {
			return 0, "", err
		}
		reader = &buf
		multipartType = writer.FormDataContentType()
	} else {
		reader = bytes.NewReader(body)
	}
	request, err := http.NewRequest(http.MethodPost, target, reader)
	if err != nil {
		return 0, "", err
	}
	hasContentType := false
	for _, header := range r.headers {
		for key, value := range header {
			name := expandTemplate(strings.TrimSpace(key), vars)
			if name == "" {
				continue
			}
			if multipartType != "" && strings.EqualFold(name, "Content-Type") {
				// multipart 的 Content-Type 必须带 boundary，不能被配置覆盖。
				continue
			}
			request.Header.Set(name, expandTemplate(value, vars))
			if strings.EqualFold(name, "Content-Type") {
				hasContentType = true
			}
		}
	}
	if multipartType != "" {
		request.Header.Set("Content-Type", multipartType)
	} else if !hasContentType {
		request.Header.Set("Content-Type", "application/octet-stream")
	}
	if filename != "" {
		request.Header.Set("X-Filename", filename)
		request.Header.Set("X-File-Name", filename)
	}
	response, err := client.Do(request)
	if err != nil {
		return 0, "", err
	}
	defer func() { _ = response.Body.Close() }()
	data, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
	return response.StatusCode, extractMessage(string(data)), nil
}

// statusLine 把状态码和服务器返回的信息拼成通知里的那一行。
func statusLine(status int, message string) string {
	message = strings.TrimSpace(message)
	if message == "" {
		return strconv.Itoa(status)
	}
	return strconv.Itoa(status) + " " + message
}

// extractMessage 从服务器返回体里挑出人能读的一句话（FastAPI/Flask 常见字段）。
func extractMessage(body string) string {
	body = strings.TrimSpace(body)
	if body == "" {
		return ""
	}
	if strings.HasPrefix(body, "{") {
		var parsed map[string]any
		if err := json.Unmarshal([]byte(body), &parsed); err == nil {
			for _, key := range []string{"detail", "message", "msg", "error"} {
				if value, ok := parsed[key]; ok {
					if text, isText := value.(string); isText && strings.TrimSpace(text) != "" {
						return cleanText(text)
					}
				}
			}
		}
	}
	return cleanText(body)
}

func cleanText(text string) string {
	text = strings.ReplaceAll(text, "\r", " ")
	text = strings.ReplaceAll(text, "\n", " ")
	return shorten(strings.TrimSpace(text), 120)
}

func shorten(text string, limit int) string {
	runes := []rune(text)
	if len(runes) <= limit {
		return text
	}
	return string(runes[:limit]) + "…"
}
