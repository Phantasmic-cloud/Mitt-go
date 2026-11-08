package mittgo

import "testing"

func TestBuildUpstreamURLOff(t *testing.T) {
	u, err := buildUpstreamURL(config{})
	if err != nil {
		t.Fatalf("disabled upstream should not error: %v", err)
	}
	if u != nil {
		t.Fatalf("disabled upstream should be direct, got %s", u)
	}
}

func TestBuildUpstreamURLDefaultPort(t *testing.T) {
	u, err := buildUpstreamURL(config{UpstreamEnable: true, UpstreamHost: "127.0.0.1"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if u.String() != "http://127.0.0.1:7890" {
		t.Fatalf("default port mismatch: %s", u)
	}
}

func TestBuildUpstreamURLAuth(t *testing.T) {
	u, err := buildUpstreamURL(config{
		UpstreamEnable: true,
		UpstreamHost:   " proxy.lan ",
		UpstreamPort:   8080,
		UpstreamUser:   "u",
		UpstreamPass:   "p@ss",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if u.Host != "proxy.lan:8080" {
		t.Fatalf("host mismatch: %s", u.Host)
	}
	user := u.User.Username()
	pass, _ := u.User.Password()
	if user != "u" || pass != "p@ss" {
		t.Fatalf("auth mismatch: %q/%q", user, pass)
	}
}

func TestBuildUpstreamURLNoAuth(t *testing.T) {
	u, err := buildUpstreamURL(config{
		UpstreamEnable: true,
		UpstreamHost:   "127.0.0.1",
		UpstreamPort:   7890,
		UpstreamUser:   "  ",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if u.User != nil {
		t.Fatalf("blank username should not add auth: %s", u)
	}
}

func TestBuildUpstreamURLIPv6(t *testing.T) {
	u, err := buildUpstreamURL(config{
		UpstreamEnable: true,
		UpstreamHost:   "[::1]",
		UpstreamPort:   7890,
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if u.Host != "[::1]:7890" {
		t.Fatalf("ipv6 host mismatch: %s", u.Host)
	}
}

func TestBuildUpstreamURLInvalid(t *testing.T) {
	cases := []struct {
		name string
		cfg  config
	}{
		{"empty host", config{UpstreamEnable: true}},
		{"bad port", config{UpstreamEnable: true, UpstreamHost: "127.0.0.1", UpstreamPort: 70000}},
		{"space in host", config{UpstreamEnable: true, UpstreamHost: "bad host", UpstreamPort: 7890}},
		{"path in host", config{UpstreamEnable: true, UpstreamHost: "proxy.lan/x", UpstreamPort: 7890}},
	}
	for _, tc := range cases {
		if _, err := buildUpstreamURL(tc.cfg); err == nil {
			t.Errorf("%s: expected error", tc.name)
		}
	}
}

func TestApplyUpstreamRuntime(t *testing.T) {
	t.Cleanup(func() { _ = applyUpstream(config{}) })
	if err := applyUpstream(config{UpstreamEnable: true, UpstreamHost: "127.0.0.1", UpstreamPort: 7890}); err != nil {
		t.Fatalf("apply enabled: %v", err)
	}
	if got := UpstreamURL(); got == nil || got.Host != "127.0.0.1:7890" {
		t.Fatalf("runtime url mismatch: %v", got)
	}
	if err := applyUpstream(config{}); err != nil {
		t.Fatalf("apply disabled: %v", err)
	}
	if got := UpstreamURL(); got != nil {
		t.Fatalf("disabled runtime should be direct, got %v", got)
	}
}
