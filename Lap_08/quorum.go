package main

import (
	"fmt"
	"math"
	"sync"
)

func validateQuorum(nr, nw int) error {
	if nr < 1 || nr > 5 || nw < 1 || nw > 5 || nr+nw <= 5 || 2*nw <= 5 {
		return fmt.Errorf("Gifford requires 1<=NR,NW<=5, NR+NW>5 and 2*NW>5; got NR=%d NW=%d", nr, nw)
	}
	return nil
}

type Quorum struct {
	mu     sync.Mutex // Một client proxy tuần tự hóa ghi; không giả vờ có consensus.
	peers  []Peer
	nr, nw int
	log    logFunc
}

func newQuorum(peers []Peer, nr, nw int, log logFunc) (*Quorum, error) {
	if len(peers) != 5 {
		return nil, fmt.Errorf("requires 5 replicas")
	}
	if err := validateQuorum(nr, nw); err != nil {
		return nil, err
	}
	return &Quorum{peers: append([]Peer(nil), peers...), nr: nr, nw: nw, log: log}, nil
}
func chooseNewest(a, b Entry) (Entry, error) {
	if a.Version == b.Version && a != b {
		return Entry{}, fmt.Errorf("conflicting entries at version %d", a.Version)
	}
	if b.Version > a.Version {
		return b, nil
	}
	return a, nil
}
func (q *Quorum) read(key string) (Entry, error) {
	if key == "" {
		return Entry{}, fmt.Errorf("empty key")
	}
	count := 0
	latest := Entry{}
	for _, peer := range q.peers {
		reply, err := rpc(peer.Address, Request{Type: "GET", Key: key})
		if err != nil {
			q.log("[QUORUM] S%d unavailable for read", peer.ID)
			continue
		}
		latest, err = chooseNewest(latest, reply.Entry)
		if err != nil {
			return Entry{}, err
		}
		count++
		q.log("[QUORUM] Read response S%d ver=%d tombstone=%v", peer.ID, reply.Entry.Version, reply.Entry.Tombstone)
		if count == q.nr {
			return latest, nil
		}
	}
	return Entry{}, fmt.Errorf("read quorum unavailable: got %d/%d responses", count, q.nr)
}
func (q *Quorum) Read(key string) (Entry, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	e, err := q.read(key)
	if err == nil {
		q.log("[CLIENT] Read %q -> value=%q ver=%d deleted=%v", key, e.Value, e.Version, e.Tombstone)
	}
	return e, err
}
func (q *Quorum) Write(key, value string, deleted bool) (Entry, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	// Hỏi read quorum trước để phiên bản mới lớn hơn lần ghi thành công trước.
	current, err := q.read(key)
	if err != nil {
		return Entry{}, err
	}
	if current.Version == math.MaxUint64 {
		return Entry{}, fmt.Errorf("version exhausted")
	}
	if deleted {
		value = ""
	}
	entry := Entry{Value: value, Version: current.Version + 1, Tombstone: deleted}
	q.log("[CLIENT] Write key=%q val=%q tombstone=%v (NW=%d) ver=%d", key, value, deleted, q.nw, entry.Version)
	count := 0
	for _, peer := range q.peers {
		reply, err := rpc(peer.Address, Request{Type: "PUT", Key: key, Entry: entry})
		if err != nil || !reply.Accepted || reply.Entry != entry {
			continue
		}
		count++
		if count == q.nw {
			q.log("[SYSTEM] Write committed to quorum: %d/%d ACKs", count, q.nw)
			return entry, nil
		}
	}
	// Các PUT đã nhận không rollback. Caller không được coi lỗi là chưa ghi đâu cả.
	return Entry{}, fmt.Errorf("write quorum unavailable: %d/%d ACKs; partial outcome may be visible", count, q.nw)
}
