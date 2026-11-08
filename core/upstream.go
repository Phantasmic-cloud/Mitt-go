package mittgo

// 上游代理（upstream proxy）
//
// 抓到的流量在 MITM 之后不是直接连目标，而是先送到一个上游 HTTP 代理再出网：
//
//	App → VPN/Tun → MITM → 上游代理 → 目标
//
// 典型用法是链到手机或局域网里已经在跑的代理端口（默认 127.0.0.1:7890），
// 这样被墙的 App 也能正常拿到响应，同时仍然能看明文。
//
// 配置可以热更新：Kotlin 侧改完立刻下发，不用停止再开始抓包。
// 只支持 http 代理（https 目标走 CONNECT）；用户名密码可选，走 Basic 认证。

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
)

// 上游代理没填端口时的默认值（和常见本地代理端口一致）。
const defaultUpstreamPort = 7890

type upstreamRuntime struct {
	url *url.URL // nil = 直连
}

var upstream atomic.Pointer[upstreamRuntime]

func init() {
	upstream.Store(&upstreamRuntime{})
}

// UpstreamURL 返回当前上游代理地址；nil 表示直连目标。
func UpstreamURL() *url.URL {
	if rt := upstream.Load(); rt != nil {
		return rt.url
	}
	return nil
}

// buildUpstreamURL 把配置转成 go-mitmproxy 认识的代理 URL。
func buildUpstreamURL(cfg config) (*url.URL, error) {
	if !cfg.UpstreamEnable {
		return nil, nil
	}
	host := strings.TrimSpace(cfg.UpstreamHost)
	if host == "" {
		return nil, errors.New("upstream proxy host is empty")
	}
	port := cfg.UpstreamPort
	if port == 0 {
		port = defaultUpstreamPort
	}
	if err := validatePort(port); err != nil {
		return nil, err
	}
	// 手输 IPv6 常带方括号，JoinHostPort 会再加一层，这里先剥掉。
	host = strings.TrimPrefix(strings.TrimSuffix(host, "]"), "[")
	if strings.ContainsAny(host, " \t/?#@") {
		return nil, fmt.Errorf("invalid upstream proxy host: %q", host)
	}
	u := &url.URL{
		Scheme: "http",
		Host:   net.JoinHostPort(host, strconv.Itoa(port)),
	}
	if user := strings.TrimSpace(cfg.UpstreamUser); user != "" {
		u.User = url.UserPassword(user, cfg.UpstreamPass)
	}
	return u, nil
}

// applyUpstream 把配置装进运行态并写一行诊断日志（不打印密码）。
func applyUpstream(cfg config) error {
	u, err := buildUpstreamURL(cfg)
	if err != nil {
		return err
	}
	upstream.Store(&upstreamRuntime{url: u})
	if u == nil {
		fmt.Fprintln(os.Stderr, "mittgo: upstream proxy off (direct)")
		return nil
	}
	auth := ""
	if u.User != nil {
		auth = fmt.Sprintf(" user=%q", u.User.Username())
	}
	fmt.Fprintf(os.Stderr, "mittgo: upstream proxy %s%s\n", u.Host, auth)
	return nil
}

// SetUpstream 热更新上游代理配置；参数是 Start 配置里同名的子集。
// 关闭时也要调用一次（upstreamEnabled=false），把旧代理清掉。
func SetUpstream(configJSON string) error {
	var cfg config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("decode upstream config: %w", err)
	}
	stateMu.Lock()
	lastConfig.UpstreamEnable = cfg.UpstreamEnable
	lastConfig.UpstreamHost = cfg.UpstreamHost
	lastConfig.UpstreamPort = cfg.UpstreamPort
	lastConfig.UpstreamUser = cfg.UpstreamUser
	lastConfig.UpstreamPass = cfg.UpstreamPass
	stateMu.Unlock()
	return applyUpstream(cfg)
}
