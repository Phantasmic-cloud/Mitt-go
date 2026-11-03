package mittgo

import (
	"encoding/base64"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	defaultMaxRecords = 500
	defaultMaxBody    = 1024 * 1024
)

type config struct {
	HTTPPort    int    `json:"httpPort"`
	TunnelPort  int    `json:"tunnelPort"`
	SocksPort   int    `json:"socksPort"`
	SocksEnable bool   `json:"socksEnabled"`
	CaDir       string `json:"caDir"`
	CaCertPem   string `json:"caCertPem"`
	CaKeyPem    string `json:"caKeyPem"`
	DomainMode  string   `json:"domainMode"`
	DomainWhite []string `json:"domainWhite"`
	DomainBlack []string `json:"domainBlack"`
	CertMode    string   `json:"certMode"`
}

type bodyView struct {
	Size      int    `json:"size"`
	Truncated bool   `json:"truncated"`
	Encoding  string `json:"encoding"`
	Data      string `json:"data"`
}

type headerView map[string][]string

type recordDetail struct {
	ID             string     `json:"id"`
	UID            int        `json:"uid"`
	PackageName    string     `json:"packageName"`
	StartedAt      int64      `json:"startedAt"`
	DurationMs     int64      `json:"durationMs"`
	Method         string     `json:"method"`
	URL            string     `json:"url"`
	Proto          string     `json:"proto"`
	RemoteAddr     string     `json:"remoteAddr"`
	StatusCode     int        `json:"statusCode"`
	RequestHeader  headerView `json:"requestHeader"`
	ResponseHeader headerView `json:"responseHeader"`
	RequestBody    bodyView   `json:"requestBody"`
	ResponseBody   bodyView   `json:"responseBody"`
	Error          string     `json:"error"`
}

type recordSummary struct {
	ID          string `json:"id"`
	UID         int    `json:"uid"`
	PackageName string `json:"packageName"`
	StartedAt   int64  `json:"startedAt"`
	DurationMs  int64  `json:"durationMs"`
	Method      string `json:"method"`
	Proto       string `json:"proto"`
	URL         string `json:"url"`
	Host        string `json:"host"`
	StatusCode  int    `json:"statusCode"`
	ContentType string `json:"contentType"`
	Size        int    `json:"size"`
	Error       string `json:"error"`
}

type snapshot struct {
	Running      bool            `json:"running"`
	HTTPPort     int             `json:"httpPort"`
	TunnelPort   int             `json:"tunnelPort"`
	SocksPort    int             `json:"socksPort"`
	SocksEnabled bool            `json:"socksEnabled"`
	Total        int             `json:"total"`
	Records      []recordSummary `json:"records"`
}

type changeEntry struct {
	Seq int64
	ID  string
}

type recordStore struct {
	mu      sync.RWMutex
	records map[string]*recordDetail
	order   []string
	max     int
	maxBody int
	// 变更流：Kotlin 按 seq 增量拉取，Go 只保留最近一小段。
	seq     int64
	changes []changeEntry
}

func newRecordStore() *recordStore {
	return &recordStore{
		records: make(map[string]*recordDetail),
		max:     defaultMaxRecords,
		maxBody: defaultMaxBody,
	}
}

func (s *recordStore) put(rec *recordDetail) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.records[rec.ID]; !ok {
		s.order = append(s.order, rec.ID)
		if len(s.order) > s.max {
			oldest := s.order[0]
			s.order = s.order[1:]
			delete(s.records, oldest)
		}
	}
	s.records[rec.ID] = rec
	s.markChanged(rec.ID)
}

// markChanged 必须在持锁时调用。
func (s *recordStore) markChanged(id string) {
	s.seq++
	s.changes = append(s.changes, changeEntry{Seq: s.seq, ID: id})
	if len(s.changes) > 4000 {
		s.changes = s.changes[len(s.changes)-4000:]
	}
}

// changesSince 返回 seq 之后的记录（每个 id 只返回最新一次）以及覆盖到的最后 seq。
func (s *recordStore) changesSince(since int64, limit int, maxBody int) (int64, []recordDetail) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	last := since
	ids := make([]string, 0, limit)
	seen := make(map[string]bool, limit)
	for _, entry := range s.changes {
		if entry.Seq <= since {
			continue
		}
		last = entry.Seq
		if !seen[entry.ID] {
			seen[entry.ID] = true
			ids = append(ids, entry.ID)
		}
		if len(ids) >= limit {
			break
		}
	}
	out := make([]recordDetail, 0, len(ids))
	for _, id := range ids {
		rec, ok := s.records[id]
		if !ok {
			continue
		}
		copied := *rec
		if maxBody > 0 {
			copied.RequestBody = truncateBody(copied.RequestBody, maxBody)
			copied.ResponseBody = truncateBody(copied.ResponseBody, maxBody)
		}
		out = append(out, copied)
	}
	return last, out
}

func (s *recordStore) update(id string, fn func(*recordDetail)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if rec, ok := s.records[id]; ok {
		fn(rec)
		s.markChanged(id)
	}
}

func (s *recordStore) clear() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.records = make(map[string]*recordDetail)
	s.order = nil
}

func (s *recordStore) get(id string) (recordDetail, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	rec, ok := s.records[id]
	if !ok {
		return recordDetail{}, false
	}
	return *rec, true
}

func (s *recordStore) list() []recordSummary {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]recordSummary, 0, len(s.order))
	for _, id := range s.order {
		rec := s.records[id]
		if rec == nil {
			continue
		}
		contentType := ""
		if rec.ResponseHeader != nil {
			contentType = firstHeader(rec.ResponseHeader, "Content-Type")
		}
		size := rec.ResponseBody.Size
		if size == 0 {
			size = bodySize(rec.ResponseHeader)
		}
		out = append(out, recordSummary{
			ID:          rec.ID,
			UID:         rec.UID,
			PackageName: rec.PackageName,
			StartedAt:   rec.StartedAt,
			DurationMs:  rec.DurationMs,
			Method:      rec.Method,
			Proto:       rec.Proto,
			URL:         rec.URL,
			Host:        hostFromURL(rec.URL),
			StatusCode:  rec.StatusCode,
			ContentType: contentType,
			Size:        size,
			Error:       rec.Error,
		})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].StartedAt > out[j].StartedAt })
	return out
}

func firstHeader(h headerView, key string) string {
	for k, values := range h {
		if strings.EqualFold(k, key) && len(values) > 0 {
			return values[0]
		}
	}
	return ""
}

func bodySize(h headerView) int {
	value := firstHeader(h, "Content-Length")
	if value == "" {
		return 0
	}
	var n int
	for _, c := range value {
		if c < '0' || c > '9' {
			return 0
		}
		n = n*10 + int(c-'0')
	}
	return n
}

func hostFromURL(raw string) string {
	withoutScheme := raw
	if idx := strings.Index(withoutScheme, "://"); idx >= 0 {
		withoutScheme = withoutScheme[idx+3:]
	}
	if idx := strings.IndexByte(withoutScheme, '/'); idx >= 0 {
		withoutScheme = withoutScheme[:idx]
	}
	return withoutScheme
}

func copyHeader(h http.Header) headerView {
	if h == nil {
		return headerView{}
	}
	out := make(headerView, len(h))
	for k, values := range h {
		out[k] = append([]string(nil), values...)
	}
	return out
}

func makeBodyView(data []byte, max int) bodyView {
	original := len(data)
	if len(data) > max {
		data = data[:max]
	}
	view := bodyView{
		Size:      original,
		Truncated: original > len(data),
	}
	if utf8.Valid(data) {
		view.Encoding = "text"
		view.Data = string(data)
	} else {
		view.Encoding = "base64"
		view.Data = base64.StdEncoding.EncodeToString(data)
	}
	return view
}

func startedAt(t time.Time) int64 {
	if t.IsZero() {
		return time.Now().UnixMilli()
	}
	return t.UnixMilli()
}
