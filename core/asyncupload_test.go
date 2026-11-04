package mittgo

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/lqqyt2423/go-mitmproxy/proxy"
	"gopkg.in/yaml.v3"
)

// 与 example.yaml 同构，只是把 server_url 换成测试服务器。
func asyncTestConfig(t *testing.T, serverURL string, maxRetries int) asyncYamlConfig {
	t.Helper()
	raw := `
enable: true
name: 演示例子
match_url:
  - "^https://example\\.com/api/user/(?<user_id>\\d+)/example(?:\\?.*)?$"
upload:
  server_url: ` + serverURL + `
  tls_skip_verify: false
  headers:
    - Content-Type: application/octet-stream
    - User-Agent: Mitt/2.0
  file:
    type: Response Body
    rename: en-{user_id}-example-{time}.bin
  timeout: 3
  max_retries: ` + strconv.Itoa(maxRetries) + `
notify:
  enabled: true
  extra_info: {user_id}
`
	var cfg asyncYamlConfig
	if err := yaml.Unmarshal([]byte(raw), &cfg); err != nil {
		t.Fatalf("yaml: %v", err)
	}
	return cfg
}

func resetAsyncQueue() {
	asyncQueue.mu.Lock()
	asyncQueue.items = nil
	asyncQueue.mu.Unlock()
}

func takeNotices() []asyncNotice {
	asyncQueue.mu.Lock()
	items := asyncQueue.items
	asyncQueue.items = nil
	asyncQueue.mu.Unlock()
	return items
}

func TestAsyncRuleCompileAndMatch(t *testing.T) {
	cfg := asyncTestConfig(t, "https://api.example.com/api/en/{user_id}/upload", 0)
	rule, err := compileAsyncRule(cfg)
	if err != nil {
		t.Fatalf("compile: %v", err)
	}
	url := "https://example.com/api/user/1234567890/example?x=1"
	match := rule.patterns[0].FindStringSubmatch(url)
	if match == nil {
		t.Fatalf("pattern did not match %s", url)
	}
	vars := map[string]string{}
	for index, name := range rule.patterns[0].SubexpNames() {
		if index == 0 || name == "" {
			continue
		}
		vars[name] = match[index]
	}
	if vars["user_id"] != "1234567890" {
		t.Fatalf("user_id = %q", vars["user_id"])
	}
	vars["time"] = "20261103-160000"
	if got := expandTemplate(rule.serverURL, vars); got != "https://api.example.com/api/en/1234567890/upload" {
		t.Fatalf("server url = %q", got)
	}
	if got := expandTemplate(rule.rename, vars); got != "en-1234567890-example-20261103-160000.bin" {
		t.Fatalf("rename = %q", got)
	}
	if rule.fromRequest {
		t.Fatalf("Response Body 应该被识别为响应体")
	}
	if rule.extraInfo != "{user_id}" {
		t.Fatalf("extra_info = %q", rule.extraInfo)
	}
}

func TestAsyncUploadPostsBody(t *testing.T) {
	resetAsyncQueue()
	var (
		gotBody     string
		gotName     string
		gotFileName string
		gotMethod   string
		gotType     string
	)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotType = r.Header.Get("Content-Type")
		gotFileName = r.Header.Get("X-Filename")
		if err := r.ParseMultipartForm(8 << 20); err == nil {
			if file, header, err := r.FormFile("file"); err == nil {
				defer file.Close()
				data, _ := io.ReadAll(file)
				gotBody = string(data)
				gotName = header.Filename
			}
		}
		_, _ = w.Write([]byte(`{"message":"上传成功"}`))
	}))
	defer server.Close()

	cfg := asyncTestConfig(t, server.URL+"/{user_id}", 0)
	rule, err := compileAsyncRule(cfg)
	if err != nil {
		t.Fatalf("compile: %v", err)
	}
	rule.upload([]byte("hello-async"), map[string]string{"user_id": "123", "time": "t0"})

	if gotMethod != http.MethodPost {
		t.Fatalf("method = %q", gotMethod)
	}
	if !strings.HasPrefix(gotType, "multipart/form-data") {
		t.Fatalf("content-type = %q", gotType)
	}
	if gotBody != "hello-async" {
		t.Fatalf("body = %q", gotBody)
	}
	if gotName != "en-123-example-t0.bin" || gotFileName != gotName {
		t.Fatalf("filename = %q / header %q", gotName, gotFileName)
	}
	notices := takeNotices()
	var title, text string
	for _, item := range notices {
		if item.Kind == "notify" {
			title, text = item.Title, item.Text
		}
	}
	if title != "Mitt-go" {
		t.Fatalf("title = %q", title)
	}
	want := "[旁路上传] 演示例子\n200 上传成功\n123"
	if text != want {
		t.Fatalf("text = %q, want %q", text, want)
	}
}

func TestAsyncUploadReportsFailure(t *testing.T) {
	resetAsyncQueue()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte(`{"detail":"rate_limit_exceeded"}`))
	}))
	defer server.Close()

	cfg := asyncTestConfig(t, server.URL, 1)
	rule, err := compileAsyncRule(cfg)
	if err != nil {
		t.Fatalf("compile: %v", err)
	}
	rule.upload([]byte("x"), map[string]string{"user_id": "42", "time": "t0"})

	notices := takeNotices()
	var notify string
	for _, item := range notices {
		if item.Kind == "notify" {
			notify = item.Text
		}
	}
	want := "[旁路上传] 演示例子\n429 rate_limit_exceeded\n重试次数达到上限\n42"
	if notify != want {
		t.Fatalf("notify = %q, want %q", notify, want)
	}
}

func TestSetAsyncUploadRejectsBrokenRegex(t *testing.T) {
	resetAsyncQueue()
	payload := `{"enabled":true,"configs":["enable: true\nname: 坏规则\nmatch_url:\n  - \"([\"\nupload:\n  server_url: https://example.com/\n"]}`
	if err := SetAsyncUpload(payload); err != "" {
		t.Fatalf("unexpected error: %s", err)
	}
	set, _ := asyncStore.Load().(*asyncSet)
	if set == nil || len(set.rules) != 0 {
		t.Fatalf("broken rule should be skipped, got %d", len(set.rules))
	}
	notices := takeNotices()
	found := false
	for _, item := range notices {
		if strings.Contains(item.Text, "无效") {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected an error log for the broken regex, got %v", notices)
	}
}

func TestAsyncSizeLimit(t *testing.T) {
	raw := `
enable: true
name: 大小过滤
match_url:
  - '^https://example\.com/big$' 
upload:
  server_url: https://example.com/upload
  file:
    type: Response Body
    rename: big-{time}.bin
    size_limit:
      min: 51200
      max: 2097152
`
	var cfg asyncYamlConfig
	if err := yaml.Unmarshal([]byte(raw), &cfg); err != nil {
		t.Fatalf("yaml: %v", err)
	}
	rule, err := compileAsyncRule(cfg)
	if err != nil {
		t.Fatalf("compile: %v", err)
	}
	if rule.minSize != 51200 || rule.maxSize != 2097152 {
		t.Fatalf("size limit = [%d,%d]", rule.minSize, rule.maxSize)
	}
	if rule.sizeAllowed(1024) {
		t.Fatalf("太小应该被拒绝")
	}
	if !rule.sizeAllowed(60000) {
		t.Fatalf("区间内应该通过")
	}
	if rule.sizeAllowed(4 << 20) {
		t.Fatalf("太大应该被拒绝")
	}

	// 只填 min：上限不限。
	onlyMin := `
enable: true
name: 只要小的
match_url:
  - '^https://example\.com/a$' 
upload:
  server_url: https://example.com/upload
  file:
    type: Response Body
    size_limit:
      min: 100
`
	var cfg2 asyncYamlConfig
	if err := yaml.Unmarshal([]byte(onlyMin), &cfg2); err != nil {
		t.Fatalf("yaml2: %v", err)
	}
	rule2, err := compileAsyncRule(cfg2)
	if err != nil {
		t.Fatalf("compile2: %v", err)
	}
	if rule2.maxSize != 0 || !rule2.sizeAllowed(50<<20) {
		t.Fatalf("只填 min 时不该限制上限：max=%d", rule2.maxSize)
	}
	if rule2.sizeAllowed(50) {
		t.Fatalf("小于 min 应该被拒绝")
	}
}

func TestAsyncChunkUpload(t *testing.T) {
	var mu sync.Mutex
	var indexes []int
	var totals []int
	var bodies []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		index, _ := strconv.Atoi(r.Header.Get("X-Chunk-Index"))
		total, _ := strconv.Atoi(r.Header.Get("X-Total-Chunks"))
		mu.Lock()
		indexes = append(indexes, index)
		totals = append(totals, total)
		bodies = append(bodies, string(body))
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"detail":"ok"}`))
	}))
	defer server.Close()

	raw := fmt.Sprintf(`
name: 分片
match_url:
  - '^https://example\.com/chunk$'
upload:
  server_url: %s/upload
  file:
    type: Response Body
    chunk_upload:
      enable: true
      chunk_size: 65536
`, server.URL)
	var cfg asyncYamlConfig
	if err := yaml.Unmarshal([]byte(raw), &cfg); err != nil {
		t.Fatalf("yaml: %v", err)
	}
	rule, err := compileAsyncRule(cfg)
	if err != nil {
		t.Fatalf("compile: %v", err)
	}
	if rule.chunkSize != 65536 {
		t.Fatalf("chunkSize = %d", rule.chunkSize)
	}

	body := bytes.Repeat([]byte("a"), 150000)
	status, _, err := rule.post(&http.Client{}, server.URL+"/upload", "", body, map[string]string{})
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	if status != http.StatusOK {
		t.Fatalf("status = %d", status)
	}
	if len(bodies) != 3 {
		t.Fatalf("expected 3 chunks, got %d", len(bodies))
	}
	if indexes[0] != 0 || indexes[1] != 1 || indexes[2] != 2 {
		t.Fatalf("chunk indexes = %v", indexes)
	}
	for _, total := range totals {
		if total != 3 {
			t.Fatalf("total chunks = %v", totals)
		}
	}
	if strings.Join(bodies, "") != string(body) {
		t.Fatalf("chunk bodies do not add up")
	}

	// 关掉分片：即使 body 很大也只发一次，且不带分片头。
	off := strings.Replace(raw, "enable: true", "enable: false", 1)
	var cfg2 asyncYamlConfig
	if err := yaml.Unmarshal([]byte(off), &cfg2); err != nil {
		t.Fatalf("yaml2: %v", err)
	}
	rule2, err := compileAsyncRule(cfg2)
	if err != nil {
		t.Fatalf("compile2: %v", err)
	}
	if rule2.chunkSize != 0 {
		t.Fatalf("chunk should be off, got %d", rule2.chunkSize)
	}
	indexes, totals, bodies = nil, nil, nil
	if _, _, err := rule2.post(&http.Client{}, server.URL+"/upload", "", body, map[string]string{}); err != nil {
		t.Fatalf("post2: %v", err)
	}
	if len(bodies) != 1 {
		t.Fatalf("expected 1 request, got %d", len(bodies))
	}
}

// 同一个 URL 允许被多份配置命中：一个包同时传两个服务器。
func TestAsyncMultiRuleSameURL(t *testing.T) {
	resetAsyncQueue()
	var mu sync.Mutex
	hits := map[string]int{}
	makeServer := func(tag string) *httptest.Server {
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			mu.Lock()
			hits[tag]++
			mu.Unlock()
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"detail":"ok"}`))
		}))
	}
	serverA := makeServer("A")
	defer serverA.Close()
	serverB := makeServer("B")
	defer serverB.Close()

	makeRule := func(name, serverURL string) asyncRule {
		raw := fmt.Sprintf(`
name: %s
match_url:
  - '^https://example\.com/api/suite$'
upload:
  server_url: %s
  file:
    type: Response Body
`, name, serverURL)
		var cfg asyncYamlConfig
		if err := yaml.Unmarshal([]byte(raw), &cfg); err != nil {
			t.Fatalf("yaml %s: %v", name, err)
		}
		rule, err := compileAsyncRule(cfg)
		if err != nil {
			t.Fatalf("compile %s: %v", name, err)
		}
		return rule
	}

	asyncStore.Store(&asyncSet{enabled: true, rules: []asyncRule{
		makeRule("外部A", serverA.URL+"/upload"),
		makeRule("自建", serverB.URL+"/upload"),
	}})
	defer asyncStore.Store(&asyncSet{})

	u, err := url.Parse("https://example.com/api/suite")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	asyncHandleFlow(&proxy.Flow{
		Request:  &proxy.Request{Method: http.MethodPost, URL: u},
		Response: &proxy.Response{Body: []byte(strings.Repeat("x", 4096))},
	})

	for i := 0; i < 200; i++ {
		mu.Lock()
		done := hits["A"] == 1 && hits["B"] == 1
		mu.Unlock()
		if done {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	mu.Lock()
	gotA, gotB := hits["A"], hits["B"]
	mu.Unlock()
	if gotA != 1 || gotB != 1 {
		t.Fatalf("两份配置都应该收到同一个包，实际 A=%d B=%d", gotA, gotB)
	}
}
