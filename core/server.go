package mittgo

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/lqqyt2423/go-mitmproxy/addon"
	"github.com/lqqyt2423/go-mitmproxy/proxy"
	"path"
	"runtime/debug"
	"sync/atomic"
)

var (
	stateMu     sync.Mutex
	activeState *engineState
	lastConfig  = config{
		HTTPPort:   9099,
		TunnelPort: 9098,
		SocksPort:  1080,
	}
	globalStore = newRecordStore()
)

type engineState struct {
	cfg      config
	proxy    *proxy.Proxy
	httpAddr string
	tunnel   net.Listener
	socks    net.Listener
	stopOnce sync.Once
	connMu   sync.Mutex
	conns    map[net.Conn]struct{}
}

func defaultConfig(cfg config) config {
	if cfg.HTTPPort == 0 {
		cfg.HTTPPort = 9099
	}
	if cfg.TunnelPort == 0 {
		cfg.TunnelPort = 9098
	}
	if cfg.SocksPort == 0 {
		cfg.SocksPort = 1080
	}
	return cfg
}

func validatePort(port int) error {
	if port < 1 || port > 65535 {
		return fmt.Errorf("invalid port: %d", port)
	}
	return nil
}

func validateCA(certPem, keyPem string) error {
	if strings.TrimSpace(certPem) == "" || strings.TrimSpace(keyPem) == "" {
		return errors.New("CA certificate or private key is empty")
	}
	if _, err := tls.X509KeyPair([]byte(certPem), []byte(keyPem)); err != nil {
		return fmt.Errorf("CA certificate/private key mismatch: %w", err)
	}
	block, _ := pem.Decode([]byte(certPem))
	if block == nil || block.Type != "CERTIFICATE" {
		return errors.New("CA certificate is not valid PEM")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return fmt.Errorf("parse CA certificate: %w", err)
	}
	if !cert.IsCA {
		return errors.New("certificate is not a CA")
	}
	return nil
}

func installFixedCA(cfg config) error {
	if cfg.CaDir == "" {
		return errors.New("CA directory is empty")
	}
	if err := validateCA(cfg.CaCertPem, cfg.CaKeyPem); err != nil {
		return err
	}
	if err := os.MkdirAll(cfg.CaDir, 0o700); err != nil {
		return fmt.Errorf("create CA directory: %w", err)
	}
	combined := strings.TrimSpace(cfg.CaKeyPem) + "\n" + strings.TrimSpace(cfg.CaCertPem) + "\n"
	path := filepath.Join(cfg.CaDir, "mitmproxy-ca.pem")
	if err := os.WriteFile(path, []byte(combined), 0o600); err != nil {
		return fmt.Errorf("install fixed CA: %w", err)
	}
	return nil
}

// Start starts the local HTTP MITM proxy, the VPN transparent ingress and,
// optionally, a local SOCKS5 endpoint. Config is JSON with ports, CA directory
// and the fixed CA PEM pair supplied by the Android shell.
func Start(configJSON string) error {
	var cfg config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("decode config: %w", err)
	}
	cfg = defaultConfig(cfg)
	// 抓包时 TLS、gzip、JSON 一直在分配内存，Go 默认 GOGC=100 会让 GC 跑得很勤。
	// 放宽到 200、堆上限压到 192 MiB：GC 次数少一半，内存也不会失控。
	debug.SetGCPercent(200)
	debug.SetMemoryLimit(192 << 20)
	if err := validatePort(cfg.HTTPPort); err != nil {
		return err
	}
	if err := validatePort(cfg.TunnelPort); err != nil {
		return err
	}
	if err := validatePort(cfg.SocksPort); err != nil {
		return err
	}
	if cfg.HTTPPort == cfg.TunnelPort || cfg.HTTPPort == cfg.SocksPort || cfg.TunnelPort == cfg.SocksPort {
		return errors.New("proxy ports must be different")
	}
	if err := installFixedCA(cfg); err != nil {
		return err
	}
	// 启动时就把域名过滤规则装好，之后 Kotlin 侧还能再改。
	SetDomainFilter(cfg.DomainMode, cfg.DomainWhite, cfg.DomainBlack)
	fmt.Fprintf(os.Stderr, "mittgo: domain filter mode=%q white=%v black=%v\n", cfg.DomainMode, cfg.DomainWhite, cfg.DomainBlack)

	stateMu.Lock()
	if activeState != nil {
		stateMu.Unlock()
		return errors.New("proxy is already running")
	}
	lastConfig = cfg
	globalStore.clear()
	stateMu.Unlock()

	httpAddr := net.JoinHostPort("127.0.0.1", strconv.Itoa(cfg.HTTPPort))
	opts := &proxy.Options{
		Addr:              httpAddr,
		CaRootPath:        cfg.CaDir,
		StreamLargeBodies: 2 * 1024 * 1024,
	}
	certMode := strings.ToUpper(strings.TrimSpace(cfg.CertMode))
	if certMode == "INDIA" {
		// 免 root 模式：用可恢复私钥的 eMudhra 中间证书签发
		opts.NewCaFunc = NewIndiaCA
	}
	fmt.Fprintf(os.Stderr, "mittgo: cert mode=%q\n", certMode)
	p, err := proxy.NewProxy(opts)
	if err != nil {
		return fmt.Errorf("create MITM proxy: %w", err)
	}
	p.AddAddon(&addon.Decoder{})
	p.AddAddon(newRecorderAddon(globalStore))
	// 上游代理：出站连接要不要走代理由运行态决定，运行中可以热改，不用重启。
	// 配置有问题时退回直连，不要因为一个设置项启动不了抓包。
	if err := applyUpstream(cfg); err != nil {
		fmt.Fprintf(os.Stderr, "mittgo: upstream proxy ignored: %v\n", err)
		_ = applyUpstream(config{})
	}
	p.SetUpstreamProxy(func(*http.Request) (*url.URL, error) {
		return UpstreamURL(), nil
	})
	// 域名过滤：命中“不抓”的域名直接走隧道，不做 MITM。
	p.SetShouldInterceptRule(func(req *http.Request) bool {
		return domainAllowed(req.URL.Hostname())
	})

	st := &engineState{
		cfg:      cfg,
		proxy:    p,
		httpAddr: httpAddr,
		conns:    make(map[net.Conn]struct{}),
	}

	proxyExited := make(chan error, 1)
	go func() {
		proxyExited <- p.Start()
	}()

	deadline := time.Now().Add(5 * time.Second)
	for {
		select {
		case err := <-proxyExited:
			if err == nil {
				err = errors.New("MITM proxy stopped unexpectedly")
			}
			return fmt.Errorf("start MITM proxy: %w", err)
		default:
		}
		conn, dialErr := net.DialTimeout("tcp", httpAddr, 120*time.Millisecond)
		if dialErr == nil {
			_ = conn.Close()
			break
		}
		if time.Now().After(deadline) {
			_ = p.Close()
			return fmt.Errorf("MITM proxy did not become ready at %s", httpAddr)
		}
		time.Sleep(40 * time.Millisecond)
	}

	tunnelAddr := net.JoinHostPort("127.0.0.1", strconv.Itoa(cfg.TunnelPort))
	tunnel, err := net.Listen("tcp", tunnelAddr)
	if err != nil {
		_ = p.Close()
		return fmt.Errorf("listen VPN ingress %s: %w", tunnelAddr, err)
	}
	st.tunnel = tunnel

	stateMu.Lock()
	activeState = st
	stateMu.Unlock()

	go st.acceptTunnel()
	if cfg.SocksEnable {
		if err := st.startSocks(); err != nil {
			_ = Stop()
			return err
		}
	}
	return nil
}

// Stop stops all listeners and the MITM proxy. Buffered records stay available.
func Stop() error {
	stateMu.Lock()
	st := activeState
	activeState = nil
	stateMu.Unlock()
	if st == nil {
		return nil
	}
	st.close()
	return nil
}

func (st *engineState) close() {
	st.stopOnce.Do(func() {
		if st.tunnel != nil {
			_ = st.tunnel.Close()
		}
		if st.socks != nil {
			_ = st.socks.Close()
		}
		st.connMu.Lock()
		for conn := range st.conns {
			_ = conn.Close()
		}
		st.conns = make(map[net.Conn]struct{})
		st.connMu.Unlock()
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = st.proxy.Shutdown(ctx)
		cancel()
		_ = st.proxy.Close()
	})
}

func (st *engineState) track(conn net.Conn) func() {
	st.connMu.Lock()
	st.conns[conn] = struct{}{}
	st.connMu.Unlock()
	return func() {
		st.connMu.Lock()
		delete(st.conns, conn)
		st.connMu.Unlock()
	}
}

func (st *engineState) acceptTunnel() {
	for {
		conn, err := st.tunnel.Accept()
		if err != nil {
			return
		}
		go st.handleTunnel(conn)
	}
}

func (st *engineState) handleTunnel(conn net.Conn) {
	untrack := st.track(conn)
	defer untrack()
	defer conn.Close()

	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	br := bufio.NewReaderSize(conn, 16*1024)
	line, err := br.ReadString('\n')
	if err != nil {
		return
	}
	if len(line) > 256 {
		return
	}
	fields := strings.Fields(line)
	if len(fields) < 3 || fields[0] != "MITT-GO1" {
		return
	}
	port, err := strconv.Atoi(fields[2])
	if err != nil || validatePort(port) != nil {
		return
	}
	meta := appMeta{}
	if len(fields) >= 5 {
		meta.uid, _ = strconv.Atoi(fields[3])
		meta.pkg = fields[4]
	}
	target := net.JoinHostPort(fields[1], strconv.Itoa(port))
	upstream, err := dialViaHTTPProxy(st.httpAddr, target, meta)
	if err != nil {
		return
	}
	defer upstream.Close()
	_ = conn.SetReadDeadline(time.Time{})

	pipeBuffered(conn, br, upstream)
}

func (st *engineState) startSocks() error {
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(st.cfg.SocksPort))
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listen SOCKS5 %s: %w", addr, err)
	}
	st.socks = ln
	go st.acceptSocks()
	return nil
}

func (st *engineState) stopSocks() {
	if st.socks != nil {
		_ = st.socks.Close()
		st.socks = nil
	}
}

func (st *engineState) acceptSocks() {
	for {
		conn, err := st.socks.Accept()
		if err != nil {
			return
		}
		go st.handleSocks(conn)
	}
}

func (st *engineState) handleSocks(conn net.Conn) {
	untrack := st.track(conn)
	defer untrack()
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	br := bufio.NewReaderSize(conn, 16*1024)

	head := make([]byte, 2)
	if _, err := io.ReadFull(br, head); err != nil || head[0] != 5 {
		return
	}
	methods := make([]byte, int(head[1]))
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(br, req); err != nil || req[0] != 5 || req[1] != 1 {
		writeSocksReply(conn, 7)
		return
	}
	var host string
	switch req[3] {
	case 1:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(br, ip); err != nil {
			return
		}
		host = net.IP(ip).String()
	case 3:
		n, err := br.ReadByte()
		if err != nil {
			return
		}
		name := make([]byte, int(n))
		if _, err := io.ReadFull(br, name); err != nil {
			return
		}
		host = string(name)
	case 4:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(br, ip); err != nil {
			return
		}
		host = net.IP(ip).String()
	default:
		writeSocksReply(conn, 8)
		return
	}
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(br, portBytes); err != nil {
		return
	}
	port := int(portBytes[0])<<8 | int(portBytes[1])
	target := net.JoinHostPort(host, strconv.Itoa(port))

	upstream, err := dialViaHTTPProxy(st.httpAddr, target, appMeta{})
	if err != nil {
		writeSocksReply(conn, 1)
		return
	}
	defer upstream.Close()
	if err := writeSocksReply(conn, 0); err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})
	pipeBuffered(conn, br, upstream)
}

func writeSocksReply(conn net.Conn, code byte) error {
	_, err := conn.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}

func dialViaHTTPProxy(proxyAddr, target string, meta appMeta) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", proxyAddr, 5*time.Second)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(8 * time.Second))
	request := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\nProxy-Connection: keep-alive\r\n"
	if meta.uid > 0 {
		request += fmt.Sprintf("%s: %d\r\n", headerAppUID, meta.uid)
	}
	if meta.pkg != "" && meta.pkg != "-" {
		safePkg := strings.NewReplacer("\r", "", "\n", "", " ", "_").Replace(meta.pkg)
		request += fmt.Sprintf("%s: %s\r\n", headerAppPkg, safePkg)
	}
	request += "\r\n"
	if _, err := io.WriteString(conn, request); err != nil {
		_ = conn.Close()
		return nil, err
	}
	br := bufio.NewReaderSize(conn, 16*1024)
	status, err := br.ReadString('\n')
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	if !strings.Contains(status, " 200 ") {
		_ = conn.Close()
		return nil, fmt.Errorf("proxy CONNECT failed: %s", strings.TrimSpace(status))
	}
	for i := 0; i < 128; i++ {
		line, err := br.ReadString('\n')
		if err != nil {
			_ = conn.Close()
			return nil, err
		}
		if line == "\r\n" || line == "\n" {
			break
		}
	}
	_ = conn.SetDeadline(time.Time{})
	return &bufferedConn{Conn: conn, reader: br}, nil
}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) {
	return c.reader.Read(p)
}

func pipeBuffered(client net.Conn, clientReader *bufio.Reader, upstream net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, clientReader)
		if tcp, ok := upstream.(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(client, upstream)
		if tcp, ok := client.(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
	<-done
}

// SetSocksEnabled starts or stops the local SOCKS5 listener while running.
func SetSocksEnabled(enabled bool) error {
	stateMu.Lock()
	st := activeState
	lastConfig.SocksEnable = enabled
	stateMu.Unlock()
	if st == nil {
		return nil
	}
	if enabled {
		if st.socks != nil {
			return nil
		}
		st.cfg.SocksEnable = true
		return st.startSocks()
	}
	st.cfg.SocksEnable = false
	st.stopSocks()
	return nil
}

// ClearRecords clears all captured flows.
func ClearRecords() {
	globalStore.clear()
}

// Changes returns records changed after since, plus the last seq covered.
// The app pulls this incrementally and owns long-term storage.
func Changes(since int64, limit int) string {
	if limit <= 0 || limit > 200 {
		limit = 30
	}
	last, records := globalStore.changesSince(since, limit, 128*1024)
	encoded, _ := json.Marshal(map[string]any{"seq": last, "records": records})
	return string(encoded)
}

func truncateBody(body bodyView, max int) bodyView {
	if body.Data == "" {
		return body
	}
	if body.Encoding == "base64" {
		limit := max * 4 / 3
		if len(body.Data) > limit {
			body.Data = body.Data[:limit]
			body.Truncated = true
		}
		return body
	}
	if len(body.Data) > max {
		body.Data = body.Data[:max]
		body.Truncated = true
	}
	return body
}

// ---------------- 域名过滤 ----------------

type domainFilterConfig struct {
	Mode  string   `json:"mode"`
	White []string `json:"white"`
	Black []string `json:"black"`
}

var domainFilter atomic.Value // domainFilterConfig

// SetDomainFilter updates the host filter. It takes effect immediately and
// applies to new CONNECT flows only, so ongoing transfers are untouched.
func SetDomainFilter(mode string, white, black []string) {
	cfg := domainFilterConfig{
		Mode:  strings.ToUpper(strings.TrimSpace(mode)),
		White: normalizeDomainPatterns(white),
		Black: normalizeDomainPatterns(black),
	}
	domainFilter.Store(cfg)
}

func normalizeDomainPatterns(in []string) []string {
	out := make([]string, 0, len(in))
	for _, item := range in {
		value := strings.ToLower(strings.TrimSpace(item))
		value = strings.TrimPrefix(value, "http://")
		value = strings.TrimPrefix(value, "https://")
		value = strings.TrimSuffix(value, "/")
		if value == "" {
			continue
		}
		out = append(out, value)
	}
	return out
}

// domainAllowed reports whether the host should be intercepted (recorded).
func domainAllowed(host string) bool {
	raw := domainFilter.Load()
	if raw == nil {
		return true
	}
	cfg, ok := raw.(domainFilterConfig)
	if !ok {
		return true
	}
	host = strings.ToLower(strings.TrimSpace(host))
	if host == "" {
		// 拿不到域名就别拦：宁可多抓一条，也不要把正常流量挡在白名单外。
		return true
	}
	if net.ParseIP(host) != nil {
		// TUN 的 CONNECT 目标常常是 IP，这时完全没有域名信息。
		// 放进来先解密，真正的判断交给解密后的请求（recorder 用真实域名判）。
		return true
	}
	switch cfg.Mode {
	case "WHITELIST":
		if len(cfg.White) == 0 {
			return true
		}
		return matchAnyDomain(host, cfg.White)
	case "BLACKLIST":
		if len(cfg.Black) == 0 {
			return true
		}
		return !matchAnyDomain(host, cfg.Black)
	default:
		return true
	}
}

func matchAnyDomain(host string, patterns []string) bool {
	for _, pattern := range patterns {
		if matchDomain(host, pattern) {
			return true
		}
	}
	return false
}

func matchDomain(host, pattern string) bool {
	if host == "" || pattern == "" {
		return false
	}
	// *.example.com 匹配 example.com 和任意子域名
	if strings.HasPrefix(pattern, "*.") {
		suffix := pattern[2:]
		return host == suffix || strings.HasSuffix(host, "."+suffix)
	}
	// 其它 * 按通配处理
	if strings.Contains(pattern, "*") {
		ok, err := path.Match(pattern, host)
		return err == nil && ok
	}
	return host == pattern
}
