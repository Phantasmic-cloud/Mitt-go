package mittgo

// 实时吞吐
//
// 隧道连接（VPN 转发来的 HTTP(S) 流、SOCKS5 入站）外面套一层计数连接：
// 从客户端读到的是上行，写给客户端的是下行。数字是累计值，Android 侧每秒
// 取一次差值算速率，只用来刷新「抓包中」那个通知，不进记录。

import (
	"fmt"
	"net"
	"sync/atomic"
)

var (
	trafficUp   atomic.Int64 // 客户端 → 内核（上行）
	trafficDown atomic.Int64 // 内核 → 客户端（下行）
	// 进 MITM 的流量：解密成功并记录的请求 / 内核报错的次数。
	mitmOK   atomic.Int64
	mitmFail atomic.Int64
)

func resetTraffic() {
	trafficUp.Store(0)
	trafficDown.Store(0)
	mitmOK.Store(0)
	mitmFail.Store(0)
}

// Traffic 返回累计上下行字节数和 MITM 成功/失败条数（JSON）；取快照，不清零。
func Traffic() string {
	return fmt.Sprintf(
		`{"up":%d,"down":%d,"ok":%d,"fail":%d}`,
		trafficUp.Load(), trafficDown.Load(), mitmOK.Load(), mitmFail.Load(),
	)
}

// countingConn 给隧道连接挂两个计数器；Close 等其余方法原样透传。
type countingConn struct {
	net.Conn
}

func (c countingConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		trafficUp.Add(int64(n))
	}
	return n, err
}

func (c countingConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	if n > 0 {
		trafficDown.Add(int64(n))
	}
	return n, err
}

// CloseWrite 透传半关闭：pipeBuffered 靠它让两头各自收到 EOF。
func (c countingConn) CloseWrite() error {
	if tcp, ok := c.Conn.(*net.TCPConn); ok {
		return tcp.CloseWrite()
	}
	return nil
}
