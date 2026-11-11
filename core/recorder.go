package mittgo

import (
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/lqqyt2423/go-mitmproxy/proxy"
)

const (
	headerAppUID = "X-Mitt-Go-Uid"
	headerAppPkg = "X-Mitt-Go-Package"
)

type appMeta struct {
	uid int
	pkg string
}

type recorderAddon struct {
	proxy.BaseAddon
	store *recordStore

	metaMu sync.Mutex
	meta   map[net.Conn]appMeta
}

func newRecorderAddon(store *recordStore) *recorderAddon {
	return &recorderAddon{
		store: store,
		meta:  make(map[net.Conn]appMeta),
	}
}

func (a *recorderAddon) Requestheaders(f *proxy.Flow) {
	if f == nil || f.Request == nil || f.Request.Method != http.MethodConnect {
		return
	}
	if f.ConnContext == nil || f.ConnContext.ClientConn == nil || f.ConnContext.ClientConn.Conn == nil {
		return
	}
	uid, _ := strconv.Atoi(f.Request.Header.Get(headerAppUID))
	pkg := strings.TrimSpace(f.Request.Header.Get(headerAppPkg))
	if uid <= 0 && (pkg == "" || pkg == "-") {
		return
	}
	a.metaMu.Lock()
	a.meta[f.ConnContext.ClientConn.Conn] = appMeta{uid: uid, pkg: pkg}
	a.metaMu.Unlock()
}

func (a *recorderAddon) ClientDisconnected(client *proxy.ClientConn) {
	if client == nil || client.Conn == nil {
		return
	}
	a.metaMu.Lock()
	delete(a.meta, client.Conn)
	a.metaMu.Unlock()
}

func (a *recorderAddon) metaFor(f *proxy.Flow) appMeta {
	if f == nil || f.ConnContext == nil || f.ConnContext.ClientConn == nil {
		return appMeta{}
	}
	conn := f.ConnContext.ClientConn.Conn
	if conn == nil {
		return appMeta{}
	}
	a.metaMu.Lock()
	meta := a.meta[conn]
	a.metaMu.Unlock()
	return meta
}

func (a *recorderAddon) Request(f *proxy.Flow) {
	if f == nil || f.Request == nil || f.Request.Method == http.MethodConnect {
		return
	}
	if !domainAllowed(flowHost(f)) {
		return
	}
	meta := a.metaFor(f)
	method := f.Request.Method
	url := ""
	if f.Request.URL != nil {
		url = f.Request.URL.String()
	}
	a.store.put(&recordDetail{
		ID:            f.Id.String(),
		UID:           meta.uid,
		PackageName:   meta.pkg,
		StartedAt:     startedAt(f.StartTime),
		Method:        method,
		URL:           url,
		Proto:         f.Request.Proto,
		RemoteAddr:    remoteAddr(f),
		RequestHeader: copyHeader(f.Request.Header),
		RequestBody:   makeBodyView(f.Request.Body, a.store.maxBody),
	})
	// 能走到这里说明请求头已经从客户端解密出来了：记一次解密成功。
	mitmOK.Add(1)
}

// remoteAddr 是真正连出去的目标地址（IP:port），不是 URL 里的域名。
func remoteAddr(f *proxy.Flow) string {
	if f == nil || f.ConnContext == nil || f.ConnContext.ServerConn == nil {
		return ""
	}
	conn := f.ConnContext.ServerConn.Conn
	if conn == nil || conn.RemoteAddr() == nil {
		return ""
	}
	return conn.RemoteAddr().String()
}

func (a *recorderAddon) Response(f *proxy.Flow) {
	if !domainAllowed(flowHost(f)) {
		return
	}

	if f == nil || f.Request == nil || f.Request.Method == http.MethodConnect {
		return
	}
	id := f.Id.String()
	a.store.update(id, func(rec *recordDetail) {
		if f.Response != nil {
			rec.StatusCode = f.Response.StatusCode
			rec.ResponseHeader = copyHeader(f.Response.Header)
			rec.ResponseBody = makeBodyView(f.Response.Body, a.store.maxBody)
		}
		rec.DurationMs = time.Since(f.StartTime).Milliseconds()
	})
	// 旁路上传：请求体和响应体都在手上了，命中规则就异步甩出去，不影响这条流量。
	asyncHandleFlow(f)
}

func (a *recorderAddon) RequestError(f *proxy.Flow, err error) {
	if !domainAllowed(flowHost(f)) {
		return
	}

	if f == nil || f.Request == nil || f.Request.Method == http.MethodConnect || err == nil {
		return
	}
	id := f.Id.String()
	a.store.update(id, func(rec *recordDetail) {
		rec.Error = err.Error()
		rec.DurationMs = time.Since(f.StartTime).Milliseconds()
	})
	mitmFail.Add(1)
}

func (a *recorderAddon) HTTPConnectError(f *proxy.Flow, err error) {
	if f == nil || f.Request == nil || err == nil {
		return
	}
	id := f.Id.String()
	a.store.update(id, func(rec *recordDetail) {
		rec.Error = err.Error()
		rec.DurationMs = time.Since(f.StartTime).Milliseconds()
	})
	// CONNECT 隧道本身失败（多半是客户端不认证书 / 对端拒绝），也算一次失败。
	mitmFail.Add(1)
}

// rawHost 返回请求行里的 Host（可能带端口）。
// 内层请求是 origin-form（GET /path），域名只在这个头里。
func rawHost(f *proxy.Flow) string {
	if f == nil || f.Request == nil {
		return ""
	}
	if raw := f.Request.Raw(); raw != nil && raw.Host != "" {
		return raw.Host
	}
	return ""
}

// flowHost 返回解密后请求的真实域名（去掉端口）。
// 优先用 Host 头，拿不到再退回 URL。
func flowHost(f *proxy.Flow) string {
	if host := rawHost(f); host != "" {
		if idx := strings.LastIndex(host, ":"); idx > 0 {
			host = host[:idx]
		}
		if host != "" {
			return host
		}
	}
	if f != nil && f.Request != nil && f.Request.URL != nil {
		if host := f.Request.URL.Hostname(); host != "" {
			return host
		}
	}
	return ""
}
