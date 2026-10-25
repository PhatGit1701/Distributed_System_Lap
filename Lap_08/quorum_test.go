package main

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"
	"time"
)

// Test binary cũng phục vụ subcommand replica để kiểm thử đúng 5 OS process.
func TestMain(m *testing.M) {
	if len(os.Args) > 1 && os.Args[1] == "replica" {
		if err := runReplica(os.Args[2:], os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func localReplicas(t *testing.T) ([]Peer, []*tcpServer) {
	t.Helper()
	var peers []Peer
	var servers []*tcpServer
	for id := 1; id <= 5; id++ {
		r := newReplica(id, func(string, ...any) {})
		s, err := listen(r.handle)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(s.Close)
		servers = append(servers, s)
		peers = append(peers, Peer{ID: id, Address: s.listener.Addr().String()})
	}
	for _, p := range peers {
		if _, err := rpc(p.Address, Request{Type: "CONFIG", Peers: peers}); err != nil {
			t.Fatal(err)
		}
	}
	return peers, servers
}
func TestFiveProcessDemoAndTrace(t *testing.T) {
	var trace bytes.Buffer
	if err := demo(&trace, 3, 3, 80*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	for _, text := range []string{"PID=", "S4 ver=0", "S5 ver=0", "Push-Pull", "Tombstone ver=2", "cannot overwrite newer tombstone", "[VERIFY] PASS"} {
		if !strings.Contains(trace.String(), text) {
			t.Errorf("missing %q", text)
		}
	}
	if count := strings.Count(trace.String(), "PID="); count != 5 {
		t.Fatalf("expected 5 child processes, got %d", count)
	}
}
func TestAllValidGiffordThresholdsAndReadIntersection(t *testing.T) {
	peers, _ := localReplicas(t)
	for nr := 1; nr <= 5; nr++ {
		for nw := 1; nw <= 5; nw++ {
			err := validateQuorum(nr, nw)
			valid := nr+nw > 5 && 2*nw > 5
			if (err == nil) != valid {
				t.Fatalf("incorrect validation NR=%d NW=%d", nr, nw)
			}
			if !valid {
				continue
			}
			q, err := newQuorum(peers, nr, nw, func(string, ...any) {})
			if err != nil {
				t.Fatal(err)
			}
			key := fmt.Sprintf("r%d-w%d", nr, nw)
			want, err := q.Write(key, "new", false)
			if err != nil {
				t.Fatal(err)
			}
			// Chọn read quorum bắt đầu từ các replica không nhận lần ghi.
			reverse := append([]Peer(nil), peers...)
			for i, j := 0, len(reverse)-1; i < j; i, j = i+1, j-1 {
				reverse[i], reverse[j] = reverse[j], reverse[i]
			}
			reader, _ := newQuorum(reverse, nr, nw, func(string, ...any) {})
			got, err := reader.Read(key)
			if err != nil || got != want {
				t.Fatalf("quorum intersection failed NR=%d NW=%d: %+v %v", nr, nw, got, err)
			}
		}
	}
}
func TestPushPullTransfersBothDirections(t *testing.T) {
	peers, _ := localReplicas(t)
	for i, key := range []string{"left", "right"} {
		if _, err := rpc(peers[i].Address, Request{Type: "PUT", Key: key, Entry: Entry{Value: key, Version: 1}}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := rpc(peers[0].Address, Request{Type: "SYNC", Target: 2}); err != nil {
		t.Fatal(err)
	}
	for _, p := range peers[:2] {
		state, err := rpc(p.Address, Request{Type: "SNAPSHOT"})
		if err != nil || len(state.Entries) != 2 {
			t.Fatalf("push OR pull half missing: %+v %v", state, err)
		}
	}
}
func TestTombstoneSurvivesStaleGossipAndClientWriteCanRecreate(t *testing.T) {
	peers, _ := localReplicas(t)
	q, _ := newQuorum(peers, 3, 3, func(string, ...any) {})
	bob, err := q.Write("k", "Bob", false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := rpc(peers[4].Address, Request{Type: "PUT", Key: "k", Entry: bob}); err != nil {
		t.Fatal(err)
	}
	deleted, err := q.Write("k", "", true)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := rpc(peers[4].Address, Request{Type: "SYNC", Target: 1}); err != nil {
		t.Fatal(err)
	}
	stale, err := rpc(peers[0].Address, Request{Type: "PUT", Key: "k", Entry: bob})
	if err != nil || stale.Accepted || stale.Entry != deleted {
		t.Fatalf("stale value resurrected: %+v %v", stale, err)
	}
	revived, err := q.Write("k", "New intentional value", false)
	if err != nil || revived.Version <= deleted.Version || revived.Tombstone {
		t.Fatal("a NEW higher-version write should be allowed")
	}
}
func TestQuorumFailureAndPartialWriteAreExplicit(t *testing.T) {
	peers, servers := localReplicas(t)
	q, _ := newQuorum(peers, 1, 5, func(string, ...any) {})
	servers[4].Close()
	if _, err := q.Write("partial", "x", false); err == nil || !strings.Contains(err.Error(), "partial") {
		t.Fatal("write quorum failure must expose uncertain partial outcome")
	}
	reply, err := rpc(peers[0].Address, Request{Type: "GET", Key: "partial"})
	if err != nil || reply.Entry.Version != 1 {
		t.Fatal("partial writes must not be described as rolled back")
	}
	servers[0].Close()
	servers[1].Close()
	reader, _ := newQuorum(peers, 3, 3, func(string, ...any) {})
	if _, err := reader.Read("partial"); err == nil {
		t.Fatal("two live responses cannot satisfy NR=3")
	}
}
func TestSameVersionConflictIsRejected(t *testing.T) {
	r := newReplica(1, func(string, ...any) {})
	r.handle(Request{Type: "PUT", Key: "k", Entry: Entry{Value: "one", Version: 1}})
	reply := r.handle(Request{Type: "EXCHANGE", Entries: map[string]Entry{
		"extra": {Value: "must-not-apply", Version: 1},
		"k":     {Value: "conflict", Version: 1},
	}})
	if reply.Error == "" || len(r.snapshot()) != 1 {
		t.Fatal("conflicting batch should fail without partial merge")
	}
}
func TestOtherDemoThresholds(t *testing.T) {
	for _, pair := range [][2]int{{2, 4}, {1, 5}} {
		if err := demo(io.Discard, pair[0], pair[1], 80*time.Millisecond); err != nil {
			t.Fatalf("NR/NW=%v: %v", pair, err)
		}
	}
}
