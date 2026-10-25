package main

import (
	"fmt"
	"sort"
	"sync"
)

type Vector [3]int

func (v Vector) Covers(required Vector) bool {
	for i := range v {
		if v[i] < required[i] {
			return false
		}
	}
	return true
}

func merge(a, b Vector) Vector {
	for i := range a {
		if b[i] > a[i] {
			a[i] = b[i]
		}
	}
	return a
}

type Write struct {
	Origin int    `json:"origin"` // 0..2, tương ứng S1..S3.
	Seq    int    `json:"seq"`
	Deps   Vector `json:"deps"`
	Key    string `json:"key"`
	Value  string `json:"value"`
}

func (w Write) ID() string    { return fmt.Sprintf("S%d:%d", w.Origin+1, w.Seq) }
func (w Write) Clock() Vector { v := w.Deps; v[w.Origin] = w.Seq; return v }
func (w Write) valid() bool {
	if w.Origin < 0 || w.Origin >= 3 || w.Key == "" || w.Seq <= 0 {
		return false
	}
	for _, n := range w.Deps {
		if n < 0 {
			return false
		}
	}
	return w.Seq == w.Deps[w.Origin]+1
}

// Tổng vector tăng theo nhân quả; origin/seq phá hòa cho các write concurrent.
// Đây là thứ tự xác định cho register, không phải so sánh vector theo từng ô.
func newer(a, b Write) bool {
	ac, bc := a.Clock(), b.Clock()
	x, y := 0, 0
	for i := range ac {
		x += ac[i]
		y += bc[i]
	}
	if x != y {
		return x > y
	}
	if a.Origin != b.Origin {
		return a.Origin > b.Origin
	}
	return a.Seq > b.Seq
}

type Request struct {
	Type     string  `json:"type"`
	Key      string  `json:"key,omitempty"`
	Value    string  `json:"value,omitempty"`
	Required Vector  `json:"required"`
	Writes   []Write `json:"writes,omitempty"`
}

type Response struct {
	Clock  Vector  `json:"clock"`
	Value  string  `json:"value"`
	Found  bool    `json:"found"`
	Writes []Write `json:"writes,omitempty"`
	Error  string  `json:"error,omitempty"`
}

type Replica struct {
	id      int
	mu      sync.Mutex
	clock   Vector
	history map[string]Write
	values  map[string]Write
	server  *tcpServer
}

func startReplica(id int) (*Replica, error) {
	r := &Replica{id: id, history: make(map[string]Write), values: make(map[string]Write)}
	s, err := listen(r.handle)
	if err != nil {
		return nil, err
	}
	r.server = s
	return r, nil
}

func (r *Replica) Address() string { return r.server.listener.Addr().String() }

func (r *Replica) apply(w Write) {
	r.history[w.ID()] = w
	r.clock[w.Origin] = w.Seq
	if old, ok := r.values[w.Key]; !ok || newer(w, old) {
		r.values[w.Key] = w
	}
}

// Chuẩn bị một batch hoàn chỉnh trước khi sửa state: batch lỗi không áp dụng nửa chừng.
func (r *Replica) applyBatch(writes []Write) error {
	pending := make(map[string]Write)
	for _, w := range writes {
		if !w.valid() {
			return fmt.Errorf("invalid write")
		}
		if old, ok := r.history[w.ID()]; ok {
			if old != w {
				return fmt.Errorf("conflicting payload for %s", w.ID())
			}
			continue
		}
		if old, ok := pending[w.ID()]; ok && old != w {
			return fmt.Errorf("conflicting batch payload")
		}
		pending[w.ID()] = w
	}
	clock := r.clock
	var ordered []Write
	for len(pending) > 0 {
		ids := make([]string, 0, len(pending))
		for id := range pending {
			ids = append(ids, id)
		}
		sort.Strings(ids)
		progress := false
		for _, id := range ids {
			w := pending[id]
			if clock.Covers(w.Deps) && clock[w.Origin]+1 == w.Seq {
				ordered = append(ordered, w)
				clock[w.Origin] = w.Seq
				delete(pending, id)
				progress = true
			}
		}
		if !progress {
			return fmt.Errorf("missing causal dependencies; no clock fast-forward allowed")
		}
	}
	for _, w := range ordered {
		r.apply(w)
	}
	return nil
}

func (r *Replica) handle(req Request) Response {
	r.mu.Lock()
	defer r.mu.Unlock()
	reply := Response{Clock: r.clock}
	for _, n := range req.Required {
		if n < 0 {
			reply.Error = "negative vector"
			return reply
		}
	}
	switch req.Type {
	case "STATE":
	case "EXPORT":
		for _, w := range r.history {
			if w.Seq <= req.Required[w.Origin] {
				reply.Writes = append(reply.Writes, w)
			}
		}
		sort.Slice(reply.Writes, func(i, j int) bool { return reply.Writes[i].ID() < reply.Writes[j].ID() })
	case "APPLY":
		if err := r.applyBatch(req.Writes); err != nil {
			reply.Error = err.Error()
		}
	case "READ", "WRITE":
		if !r.clock.Covers(req.Required) {
			reply.Error = "STALE: replica does not cover session dependencies"
			return reply
		}
		if req.Key == "" {
			reply.Error = "empty key"
			return reply
		}
		if req.Type == "WRITE" {
			w := Write{Origin: r.id - 1, Seq: r.clock[r.id-1] + 1, Deps: r.clock, Key: req.Key, Value: req.Value}
			r.apply(w)
			reply.Writes = []Write{w}
		}
		w, ok := r.values[req.Key]
		reply.Found = ok
		reply.Value = w.Value
	default:
		reply.Error = "unknown request"
	}
	reply.Clock = r.clock
	return reply
}
