package mittgo

import (
	"crypto/x509"
	"encoding/pem"
	"os"
	"testing"
	"time"
)

// 验证：印度模式签发的 leaf 能被「只信任 AAA Certificate Services」的验证器接受。
func TestIndiaCAChain(t *testing.T) {
	ca, err := NewIndiaCA()
	if err != nil {
		t.Fatalf("NewIndiaCA: %v", err)
	}
	leaf, err := ca.GetCert("example.com")
	if err != nil {
		t.Fatalf("GetCert: %v", err)
	}
	if len(leaf.Certificate) != 2 {
		t.Fatalf("want leaf+intermediate, got %d certs", len(leaf.Certificate))
	}
	leafCert, err := x509.ParseCertificate(leaf.Certificate[0])
	if err != nil {
		t.Fatalf("parse leaf: %v", err)
	}
	inter, err := x509.ParseCertificate(leaf.Certificate[1])
	if err != nil {
		t.Fatalf("parse intermediate: %v", err)
	}
	if err := leafCert.CheckSignatureFrom(inter); err != nil {
		t.Fatalf("leaf not signed by intermediate: %v", err)
	}
	if leafCert.NotAfter.Before(time.Now()) {
		t.Fatal("leaf already expired")
	}

	// 用系统里的 AAA root 验证整条链
	rootPem, err := os.ReadFile("/etc/ssl/certs/Comodo_AAA_Services_root.pem")
	if err != nil {
		t.Skip("AAA root not available on this host")
	}
	block, _ := pem.Decode(rootPem)
	if block == nil {
		t.Skip("cannot decode AAA root")
	}
	root, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Skip("cannot parse AAA root")
	}
	pool := x509.NewCertPool()
	pool.AddCert(root)
	interPool := x509.NewCertPool()
	interPool.AddCert(inter)
	if _, err := leafCert.Verify(x509.VerifyOptions{
		Roots:         pool,
		Intermediates: interPool,
		DNSName:       "example.com",
	}); err != nil {
		t.Fatalf("verify chain: %v", err)
	}
	t.Log("chain verifies against AAA Certificate Services")
}