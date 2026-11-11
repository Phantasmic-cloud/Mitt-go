package mittgo

import (
	"encoding/json"
	"net"
	"testing"
)

func TestTrafficCountsBothDirections(t *testing.T) {
	resetTraffic()
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()

	conn := countingConn{Conn: left}
	go func() { _, _ = right.Write([]byte("hello")) }()
	buf := make([]byte, 5)
	if _, err := conn.Read(buf); err != nil {
		t.Fatalf("read: %v", err)
	}
	go func() { _, _ = right.Read(make([]byte, 6)) }()
	if _, err := conn.Write([]byte("world!")); err != nil {
		t.Fatalf("write: %v", err)
	}

	var got struct {
		Up   int64 `json:"up"`
		Down int64 `json:"down"`
		OK   int64 `json:"ok"`
		Fail int64 `json:"fail"`
	}
	if err := json.Unmarshal([]byte(Traffic()), &got); err != nil {
		t.Fatalf("json: %v", err)
	}
	if got.Up != 5 {
		t.Fatalf("up = %d, want 5", got.Up)
	}
	if got.Down != 6 {
		t.Fatalf("down = %d, want 6", got.Down)
	}

	// reset 之后归零：每次 Start 都是新会话。
	resetTraffic()
	if err := json.Unmarshal([]byte(Traffic()), &got); err != nil {
		t.Fatalf("json2: %v", err)
	}
	if got.Up != 0 || got.Down != 0 || got.OK != 0 || got.Fail != 0 {
		t.Fatalf("after reset = %d/%d/%d/%d", got.Up, got.Down, got.OK, got.Fail)
	}

	// 记录层的成功/失败计数走同一份快照。
	mitmOK.Add(3)
	mitmFail.Add(1)
	if err := json.Unmarshal([]byte(Traffic()), &got); err != nil {
		t.Fatalf("json3: %v", err)
	}
	if got.OK != 3 || got.Fail != 1 {
		t.Fatalf("mitm counts = %d/%d", got.OK, got.Fail)
	}
}
