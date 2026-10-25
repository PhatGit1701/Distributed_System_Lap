package main

import (
	"bytes"
	"context"
	"strings"
	"sync"
	"testing"
	"time"
)

func testCluster(t *testing.T) *Cluster {
	t.Helper()
	c, err := startCluster(time.Hour, func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(c.Close)
	return c
}
func ctx(t *testing.T) context.Context {
	t.Helper()
	c, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	t.Cleanup(cancel)
	return c
}

func TestDemoBothGuaranteesAndTrace(t *testing.T) {
	var trace bytes.Buffer
	if err := demo(&trace, time.Hour); err != nil {
		t.Fatal(err)
	}
	for _, text := range []string{"Validation Failed", "Fetch write-set", "Read-Your-Writes preserved", "Monotonic Reads preserved", "[VERIFY] PASS"} {
		if !strings.Contains(trace.String(), text) {
			t.Errorf("missing %q", text)
		}
	}
}
func TestReadYourWritesRepairsStaleReplica(t *testing.T) {
	c := testCluster(t)
	s := &Session{}
	context := ctx(t)
	if _, err := c.Proxy.Write(context, s, 1, "profile", "Alice"); err != nil {
		t.Fatal(err)
	}
	stale, err := rpc(c.Proxy.addresses[1], Request{Type: "READ", Key: "profile"})
	if err != nil || stale.Found || stale.Clock != (Vector{}) {
		t.Fatal("S2 must initially be stale")
	}
	reply, err := c.Proxy.Read(context, s, 2, "profile")
	if err != nil || reply.Value != "Alice" || !reply.Clock.Covers(Vector{1, 0, 0}) {
		t.Fatalf("RYW failed: %+v %v", reply, err)
	}
}
func TestMonotonicReadsWithoutOwnWrite(t *testing.T) {
	c := testCluster(t)
	reader, writer := &Session{}, &Session{}
	context := ctx(t)
	if _, err := c.Proxy.Write(context, writer, 1, "k", "seen"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Proxy.Read(context, reader, 1, "k"); err != nil {
		t.Fatal(err)
	}
	reply, err := c.Proxy.Read(context, reader, 3, "k")
	if err != nil || reply.Value != "seen" {
		t.Fatalf("MR failed: %+v %v", reply, err)
	}
	_, write := reader.Vectors()
	if write != (Vector{}) {
		t.Fatal("read-only client must not acquire a write vector")
	}
}
func TestConcurrentVectorsNeedComponentWiseValidation(t *testing.T) {
	if (Vector{0, 2, 0}).Covers(Vector{1, 0, 0}) {
		t.Fatal("larger sum cannot replace component-wise dominance")
	}
	c := testCluster(t)
	context := ctx(t)
	a, b := &Session{}, &Session{}
	if _, err := c.Proxy.Write(context, a, 1, "a", "one"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Proxy.Write(context, b, 2, "b", "two"); err != nil {
		t.Fatal(err)
	}
	reply, err := c.Proxy.Read(context, a, 2, "a")
	if err != nil || reply.Value != "one" || reply.Clock != (Vector{1, 1, 0}) {
		t.Fatalf("incomparable repair failed: %+v %v", reply, err)
	}
}
func TestUnavailableWriteSetBlocksInsteadOfReturningStale(t *testing.T) {
	c := testCluster(t)
	s := &Session{}
	context := ctx(t)
	if _, err := c.Proxy.Write(context, s, 1, "k", "must-see"); err != nil {
		t.Fatal(err)
	}
	c.replicas[0].server.Close()
	timeout, cancel := contextWithShortDeadline()
	defer cancel()
	if _, err := c.Proxy.Read(timeout, s, 2, "k"); err == nil {
		t.Fatal("must not return stale data when write-set source is gone")
	}
	read, write := s.Vectors()
	if read != (Vector{}) || write != (Vector{1, 0, 0}) {
		t.Fatal("failed read changed session")
	}
}
func contextWithShortDeadline() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), 200*time.Millisecond)
}
func TestBatchRejectsMissingDependenciesAndConflictingIDs(t *testing.T) {
	c := testCluster(t)
	r := c.replicas[2]
	second := Write{Origin: 0, Seq: 2, Deps: Vector{1, 0, 0}, Key: "k", Value: "second"}
	if _, err := rpc(r.Address(), Request{Type: "APPLY", Writes: []Write{second}}); err == nil {
		t.Fatal("must not fast-forward a missing prefix")
	}
	first := Write{Origin: 0, Seq: 1, Key: "k", Value: "first"}
	reply, err := rpc(r.Address(), Request{Type: "APPLY", Writes: []Write{second, first}})
	if err != nil || reply.Clock != (Vector{2, 0, 0}) {
		t.Fatalf("out-of-order batch not resolved: %+v %v", reply, err)
	}
	first.Value = "corrupt"
	if _, err := rpc(r.Address(), Request{Type: "APPLY", Writes: []Write{first}}); err == nil {
		t.Fatal("conflicting operation ID accepted")
	}
}
func TestSessionIsolationAndConcurrentUse(t *testing.T) {
	c := testCluster(t)
	context := ctx(t)
	a, b := &Session{}, &Session{}
	if _, err := c.Proxy.Write(context, a, 1, "k", "a"); err != nil {
		t.Fatal(err)
	}
	reply, err := c.Proxy.Read(context, b, 3, "k")
	if err != nil || reply.Found {
		t.Fatal("independent empty session may read a stale empty replica")
	}
	var wg sync.WaitGroup
	for i := 0; i < 6; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := c.Proxy.Read(context, a, 2, "k"); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
}
func TestAsyncReplicationConverges(t *testing.T) {
	c, err := startCluster(20*time.Millisecond, func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Proxy.Write(ctx(t), &Session{}, 1, "k", "async"); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		reply, err := rpc(c.Proxy.addresses[2], Request{Type: "READ", Key: "k"})
		if err == nil && reply.Found && reply.Value == "async" {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("background replication did not converge")
}
func TestConcurrentWritesConvergeRegardlessOfArrivalOrder(t *testing.T) {
	a := Write{Origin: 0, Seq: 1, Key: "k", Value: "a"}
	b := Write{Origin: 1, Seq: 1, Key: "k", Value: "b"}
	c := testCluster(t)
	for i, writes := range [][]Write{{a, b}, {b, a}} {
		if _, err := rpc(c.Proxy.addresses[i], Request{Type: "APPLY", Writes: writes}); err != nil {
			t.Fatal(err)
		}
		reply, err := rpc(c.Proxy.addresses[i], Request{Type: "READ", Key: "k"})
		if err != nil || reply.Value != "b" {
			t.Fatalf("deterministic merge failed: %+v %v", reply, err)
		}
	}
}
