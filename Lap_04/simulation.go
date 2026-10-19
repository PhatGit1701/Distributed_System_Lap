package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

const nodeCount = 5

// Logger chung chỉ phục vụ console; không trao đổi trạng thái bầu cử.
type traceLogger struct {
	mu  sync.Mutex
	out io.Writer
	err error
}

func (l *traceLogger) printf(format string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.err == nil {
		_, l.err = fmt.Fprintf(l.out, format+"\n", args...)
	}
}

func (l *traceLogger) failure() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.err
}

func createCluster(timing Timing, log logFunc) ([]*Node, error) {
	listeners := make([]net.Listener, 0, nodeCount)
	peers := make(map[int]string)
	for id := 1; id <= nodeCount; id++ {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			for _, opened := range listeners {
				_ = opened.Close()
			}
			return nil, fmt.Errorf("listen for P%d: %w", id, err)
		}
		listeners = append(listeners, listener)
		peers[id] = listener.Addr().String()
	}
	nodes := make([]*Node, 0, nodeCount)
	for i, listener := range listeners {
		nodes = append(nodes, newNode(i+1, listener, peers, nodeCount, timing, log))
	}
	for _, node := range nodes {
		node.Start()
		log("[SYSTEM] Started P%d at %s", node.id, node.listener.Addr())
	}
	return nodes, nil
}

func stopCluster(nodes []*Node) {
	for _, node := range nodes {
		node.Stop() // Idempotent: gọi lại Stop trên P5 không gây lỗi.
	}
}

func allRecognize(nodes []*Node, leader int) bool {
	for _, node := range nodes {
		if node.Leader() != leader {
			return false
		}
	}
	return true
}

func pause(ctx context.Context, duration time.Duration) error {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Phải hội tụ VÀ giữ leader ổn định trong stableFor, không chỉ kiểm tra một lần.
func waitForStableLeader(ctx context.Context, nodes []*Node, leader int, stableFor time.Duration) error {
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	var stableSince time.Time
	for {
		select {
		case <-ctx.Done():
			return fmt.Errorf("P%d did not become a stable coordinator: %w", leader, ctx.Err())
		case now := <-ticker.C:
			if !allRecognize(nodes, leader) {
				stableSince = time.Time{}
				continue
			}
			if stableSince.IsZero() {
				stableSince = now
			}
			if now.Sub(stableSince) >= stableFor {
				return nil
			}
		}
	}
}

func simulate(ctx context.Context, out io.Writer, crashAfter time.Duration) error {
	timing := defaultTiming()
	logger := &traceLogger{out: out}
	nodes, err := createCluster(timing, logger.printf)
	if err != nil {
		return err
	}
	defer stopCluster(nodes)
	logger.printf("[SYSTEM] Initial coordinator: P5. TCP heartbeat every %s.", timing.Heartbeat)
	if err := pause(ctx, crashAfter); err != nil {
		return err
	}

	// Chỉ dừng P5. Không báo crash hay gọi bầu cử trên các node khác.
	nodes[4].Stop()
	logger.printf("[SYSTEM] Active Leader P5 terminated.")
	verifyCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := waitForStableLeader(verifyCtx, nodes[:4], 4, 2*timing.Heartbeat); err != nil {
		return err
	}
	for _, node := range nodes[:4] {
		logger.printf("[VERIFY] P%d recognizes leader P4", node.id)
	}
	logger.printf("[VERIFY] PASS: P5 stopped; P1..P4 elected stable leader P4 automatically.")
	// Chờ hết worker trước khi kiểm tra lỗi ghi trace và trả về.
	stopCluster(nodes)
	return logger.failure()
}
