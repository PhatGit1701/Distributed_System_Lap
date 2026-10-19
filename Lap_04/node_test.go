package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

func testCluster(t *testing.T) []*Node {
	t.Helper()
	nodes, err := createCluster(defaultTiming(), func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { stopCluster(nodes) })
	return nodes
}

func TestCrashElectsP4AndLogsProtocol(t *testing.T) {
	var trace bytes.Buffer
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	if err := simulate(ctx, &trace, 400*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	for _, expected := range []string{
		"Active Leader P5 terminated", "Timeout detected!", "Send ELECTION",
		"Reply OK", "Recv OK", "[P4] Timeout waiting for higher IDs -> NEW LEADER!",
		"Broadcast COORDINATOR", "[VERIFY] PASS",
	} {
		if !strings.Contains(trace.String(), expected) {
			t.Errorf("missing trace: %s", expected)
		}
	}
	for id := 1; id <= 4; id++ {
		// Mỗi follower phải nhận COORDINATOR qua socket, không được main gán leader.
		if id < 4 && !strings.Contains(trace.String(), fmt.Sprintf("[P%d] Recv COORDINATOR from P4", id)) {
			t.Errorf("P%d did not receive victory over TCP", id)
		}
	}
}

func TestHealthyLeaderAndSocketShutdown(t *testing.T) {
	nodes := testCluster(t)
	time.Sleep(2 * defaultTiming().Heartbeat)
	if !allRecognize(nodes, 5) {
		t.Fatal("healthy P5 was incorrectly suspected")
	}
	address := nodes[4].listener.Addr().String()
	response, err := call(address, Message{Type: msgPing, From: 1}, defaultTiming().RPC)
	if err != nil || response.Type != msgAlive {
		t.Fatalf("healthy P5 did not answer: %+v, %v", response, err)
	}
	nodes[4].Stop()
	if _, err := call(address, Message{Type: msgPing, From: 1}, defaultTiming().RPC); err == nil {
		t.Fatal("P5 still accepts TCP after Stop")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := waitForStableLeader(ctx, nodes[:4], 4, 2*defaultTiming().Heartbeat); err != nil {
		t.Fatal(err)
	}
	nodes[4].Stop() // Dừng lặp không panic và không deadlock.
}

func TestTCPDeadlineWhenPeerDoesNotReply(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	done := make(chan struct{})
	go func() {
		defer close(done)
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(time.Second))
		var message Message
		_ = json.NewDecoder(conn).Decode(&message)
		// Nhận request nhưng không trả ALIVE. EOF khi client hết hạn và đóng.
		_, _ = io.Copy(io.Discard, conn)
	}()
	_, err = call(listener.Addr().String(), Message{Type: msgPing, From: 1}, 80*time.Millisecond)
	if err == nil {
		t.Fatal("unresponsive peer did not cause a timeout")
	}
	if netErr, ok := err.(net.Error); !ok || !netErr.Timeout() {
		t.Fatalf("expected timeout, got %v", err)
	}
	<-done
}

// Kiểm thử máy trạng thái không khởi động worker: membership chỉ có chính nó.
func isolatedNode(t *testing.T) *Node {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	node := newNode(4, listener, map[int]string{4: listener.Addr().String()},
		4, defaultTiming(), func(string, ...any) {})
	t.Cleanup(node.Stop)
	return node
}

func TestOKWithoutCoordinatorRetriesElection(t *testing.T) {
	node := isolatedNode(t)
	node.startElection("test")
	firstRound := node.round
	node.onResult(rpcResult{kind: msgElection, target: 5, round: firstRound, ok: true})
	node.onTick(node.deadline.Add(time.Millisecond))
	if node.state != waitingCoordinator {
		t.Fatalf("OK must make node wait for COORDINATOR; got %s", node.state)
	}
	node.onTick(node.deadline.Add(time.Millisecond))
	if node.state != waitingOK || node.round != firstRound+1 || node.higherOK {
		t.Fatal("COORDINATOR timeout did not start a clean election round")
	}
}

func TestStaleOKCannotPreventVictory(t *testing.T) {
	node := isolatedNode(t)
	node.startElection("test")
	node.onResult(rpcResult{kind: msgElection, target: 5, round: node.round - 1, ok: true})
	node.onTick(node.deadline.Add(time.Millisecond))
	if node.Leader() != 4 || node.state != idle {
		t.Fatal("stale OK incorrectly prevented P4 from winning")
	}
}

func TestUnknownAndLowerCoordinatorAreRejected(t *testing.T) {
	node := isolatedNode(t)
	if node.onMessage(Message{Type: msgCoordinator, From: 99}).Type != msgReject {
		t.Fatal("unknown sender accepted")
	}
	node.peers[2] = "unused" // Chỉ dùng để kiểm tra xác thực ID trong onMessage.
	if node.onMessage(Message{Type: msgCoordinator, From: 2}).Type != msgReject {
		t.Fatal("lower-ID coordinator accepted")
	}
	if node.state != waitingOK {
		t.Fatal("higher node did not challenge a lower-ID coordinator")
	}
}

func TestCancellationStopsCluster(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := simulate(ctx, io.Discard, time.Second); err == nil {
		t.Fatal("cancelled simulation unexpectedly succeeded")
	}
}

func TestRepeatedSimulations(t *testing.T) {
	if testing.Short() {
		t.Skip("repeat socket integration scenario")
	}
	for i := 0; i < 3; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
		err := simulate(ctx, io.Discard, 0)
		cancel()
		if err != nil {
			t.Fatalf("run %d: %v", i, err)
		}
	}
}
