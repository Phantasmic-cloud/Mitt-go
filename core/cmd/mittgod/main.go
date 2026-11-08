package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"net"
	"sync"
	"time"
	"unsafe"

	mittgo "mittgo"
)

// The Go core now lives inside the app process as libmittgocore.so, so the
// entry points below are plain C functions that the JNI bridge
// (jni_bridge.c) forwards to. Strings are handed back as C allocations that
// the caller must release with MittGoFree.

var startMu sync.Mutex

//export MittGoStart
func MittGoStart(configJSON *C.char) *C.char {
	startMu.Lock()
	defer startMu.Unlock()
	if configJSON == nil {
		return C.CString("config is required")
	}
	installResolver("223.5.5.5")
	if err := mittgo.Start(C.GoString(configJSON)); err != nil {
		return C.CString(err.Error())
	}
	return C.CString("")
}

//export MittGoStop
func MittGoStop() {
	startMu.Lock()
	defer startMu.Unlock()
	_ = mittgo.Stop()
}

//export MittGoEvents
func MittGoEvents(since C.longlong, limit C.int) *C.char {
	return C.CString(mittgo.Changes(int64(since), int(limit)))
}

//export MittGoClear
func MittGoClear() {
	mittgo.ClearRecords()
}

//export MittGoSetSocks
func MittGoSetSocks(enabled C.int) *C.char {
	if err := mittgo.SetSocksEnabled(enabled != 0); err != nil {
		return C.CString(err.Error())
	}
	return C.CString("")
}

//export MittGoSetDomainFilter
func MittGoSetDomainFilter(configJSON *C.char) *C.char {
	if configJSON == nil {
		return C.CString("config is required")
	}
	var cfg struct {
		Mode  string `json:"mode"`
		White []string `json:"white"`
		Black []string `json:"black"`
	}
	if err := json.Unmarshal([]byte(C.GoString(configJSON)), &cfg); err != nil {
		return C.CString("decode domain filter: " + err.Error())
	}
	mittgo.SetDomainFilter(cfg.Mode, cfg.White, cfg.Black)
	return C.CString("")
}

//export MittGoSetUpstream
func MittGoSetUpstream(configJSON *C.char) *C.char {
	if configJSON == nil {
		return C.CString("config is required")
	}
	if err := mittgo.SetUpstream(C.GoString(configJSON)); err != nil {
		return C.CString(err.Error())
	}
	return C.CString("")
}

//export MittGoSetAsyncUpload
func MittGoSetAsyncUpload(configJSON *C.char) *C.char {
	if configJSON == nil {
		return C.CString("config is required")
	}
	if err := mittgo.SetAsyncUpload(C.GoString(configJSON)); err != "" {
		return C.CString(err)
	}
	return C.CString("")
}

//export MittGoAsyncEvents
func MittGoAsyncEvents() *C.char {
	return C.CString(mittgo.AsyncEvents())
}

//export MittGoFree
func MittGoFree(p *C.char) {
	C.free(unsafe.Pointer(p))
}

// main exists only because c-shared still expects a main package.
func main() {}

func installResolver(dnsServer string) {
	address := net.JoinHostPort(dnsServer, "53")
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			dialer := net.Dialer{Timeout: 5 * time.Second}
			return dialer.DialContext(ctx, "udp", address)
		},
	}
}