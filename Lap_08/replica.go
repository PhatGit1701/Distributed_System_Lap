package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"sort"
	"sync"
	"time"
)

type Entry struct {
	Value     string `json:"value"`
	Version   uint64 `json:"version"`
	Tombstone bool   `json:"is_tombstone"`
}
type Peer struct {
	ID      int    `json:"id"`
	Address string `json:"address"`
}
type Request struct {
	Type    string           `json:"type"`
	Key     string           `json:"key,omitempty"`
	Entry   Entry            `json:"entry"`
	Entries map[string]Entry `json:"entries,omitempty"`
	Peers   []Peer           `json:"peers,omitempty"`
	Enabled bool             `json:"enabled"`
	Target  int              `json:"target,omitempty"`
}
type Response struct {
	Entry    Entry            `json:"entry"`
	Entries  map[string]Entry `json:"entries,omitempty"`
	Accepted bool             `json:"accepted"`
	Error    string           `json:"error,omitempty"`
}
type logFunc func(string, ...any)

type Replica struct {
	id       int
	mu       sync.Mutex
	gossipMu sync.Mutex
	data     map[string]Entry
	peers    []Peer
	enabled  bool
	log      logFunc
	stop     chan struct{}
	once     sync.Once
}

func newReplica(id int, log logFunc) *Replica {
	return &Replica{id: id, data: make(map[string]Entry), log: log, stop: make(chan struct{})}
}
func validEntry(key string, e Entry) bool {
	return key != "" && e.Version > 0 && (!e.Tombstone || e.Value == "")
}
func mergeInto(data map[string]Entry, key string, e Entry) error {
	if !validEntry(key, e) {
		return fmt.Errorf("invalid entry")
	}
	old, exists := data[key]
	if exists && old.Version == e.Version && old != e {
		return fmt.Errorf("conflicting payloads at version %d for %q", e.Version, key)
	}
	if !exists || e.Version > old.Version {
		data[key] = e
	}
	return nil
}
func clone(data map[string]Entry) map[string]Entry {
	copy := make(map[string]Entry, len(data))
	for k, v := range data {
		copy[k] = v
	}
	return copy
}
func (r *Replica) snapshot() map[string]Entry {
	r.mu.Lock()
	defer r.mu.Unlock()
	return clone(r.data)
}
func (r *Replica) merge(entries map[string]Entry) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	next := clone(r.data)
	for k, e := range entries {
		if err := mergeInto(next, k, e); err != nil {
			return err
		}
	}
	r.data = next
	return nil
}
func (r *Replica) exchange(target Peer) error {
	// Push state; peer merges and returns its COMPLETE state for the pull half.
	reply, err := rpc(target.Address, Request{Type: "EXCHANGE", Entries: r.snapshot()})
	if err != nil {
		return err
	}
	if err = r.merge(reply.Entries); err != nil {
		return err
	}
	r.log("[GOSSIP] S%d Push-Pull Anti-Entropy with S%d completed", r.id, target.ID)
	return nil
}
func (r *Replica) handle(req Request) Response {
	switch req.Type {
	case "GET":
		r.mu.Lock()
		defer r.mu.Unlock()
		if req.Key == "" {
			return Response{Error: "empty key"}
		}
		return Response{Entry: r.data[req.Key]}
	case "PUT":
		r.mu.Lock()
		defer r.mu.Unlock()
		if err := mergeInto(r.data, req.Key, req.Entry); err != nil {
			return Response{Error: err.Error()}
		}
		stored := r.data[req.Key]
		accepted := stored == req.Entry
		if accepted {
			r.log("[S%d] Write ACK key=%q ver=%d tombstone=%v", r.id, req.Key, stored.Version, stored.Tombstone)
		}
		return Response{Entry: stored, Accepted: accepted}
	case "SNAPSHOT":
		return Response{Entries: r.snapshot()}
	case "EXCHANGE":
		if err := r.merge(req.Entries); err != nil {
			return Response{Error: err.Error()}
		}
		return Response{Entries: r.snapshot(), Accepted: true}
	case "CONFIG":
		if len(req.Peers) != 5 {
			return Response{Error: "exactly 5 peers required"}
		}
		peers := append([]Peer(nil), req.Peers...)
		sort.Slice(peers, func(i, j int) bool { return peers[i].ID < peers[j].ID })
		for i, p := range peers {
			if p.ID != i+1 || p.Address == "" {
				return Response{Error: "invalid membership"}
			}
		}
		r.mu.Lock()
		r.peers = peers
		r.enabled = req.Enabled
		r.mu.Unlock()
		return Response{Accepted: true}
	case "ENABLE_GOSSIP":
		r.gossipMu.Lock()
		defer r.gossipMu.Unlock()
		r.mu.Lock()
		r.enabled = req.Enabled
		r.mu.Unlock()
		return Response{Accepted: true}
	case "SYNC":
		r.mu.Lock()
		peers := append([]Peer(nil), r.peers...)
		r.mu.Unlock()
		for _, peer := range peers {
			if peer.ID == req.Target && peer.ID != r.id {
				if err := r.exchange(peer); err != nil {
					return Response{Error: err.Error()}
				}
				return Response{Accepted: true}
			}
		}
		return Response{Error: "unknown/self sync target"}
	case "STOP":
		r.once.Do(func() { close(r.stop) })
		return Response{Accepted: true}
	default:
		return Response{Error: "unknown request"}
	}
}
func (r *Replica) gossip(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	rng := rand.New(rand.NewSource(int64(r.id)))
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			r.gossipMu.Lock()
			r.mu.Lock()
			peers := append([]Peer(nil), r.peers...)
			enabled := r.enabled
			r.mu.Unlock()
			if !enabled || len(peers) < 2 {
				r.gossipMu.Unlock()
				continue
			}
			var others []Peer
			for _, p := range peers {
				if p.ID != r.id {
					others = append(others, p)
				}
			}
			peer := others[rng.Intn(len(others))]
			if err := r.exchange(peer); err != nil {
				r.log("[GOSSIP] S%d -> S%d unavailable: %v", r.id, peer.ID, err)
			}
			r.gossipMu.Unlock()
		}
	}
}

// Chế độ replica chạy trong MỘT OS PROCESS riêng, do process demo khởi động.
func runReplica(args []string, out io.Writer) error {
	flags := flag.NewFlagSet("replica", flag.ContinueOnError)
	id := flags.Int("id", 0, "replica ID")
	interval := flags.Duration("interval", 2*time.Second, "gossip interval")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *id < 1 || *id > 5 || *interval <= 0 {
		return fmt.Errorf("invalid replica settings")
	}
	var logMu sync.Mutex
	log := func(f string, a ...any) { logMu.Lock(); defer logMu.Unlock(); fmt.Fprintf(out, f+"\n", a...) }
	r := newReplica(*id, log)
	server, err := listen(r.handle)
	if err != nil {
		return err
	}
	defer server.Close()
	// Parent đọc READY để biết cổng do OS cấp, không dùng file hay cổng hard-code.
	log("READY %s", server.listener.Addr())
	ctx, cancel := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	wg.Add(1)
	go func() { defer wg.Done(); r.gossip(ctx, *interval) }()
	<-r.stop
	cancel()
	wg.Wait()
	return nil
}
