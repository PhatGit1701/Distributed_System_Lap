package main

import (
	"fmt"
	"io"
	"net"
	"sync"
)

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

type lab struct {
	coordinator  *Coordinator
	participants []*Participant
	directory    string
	log          logFunc
}

func startLab(directory string, log logFunc) (*lab, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	l := &lab{directory: directory, log: log}
	var peers []Peer
	for id := 1; id <= 3; id++ {
		p, err := startParticipant(id, "127.0.0.1:0", directory, listener.Addr().String(), log)
		if err != nil {
			listener.Close()
			l.Close()
			return nil, err
		}
		l.participants = append(l.participants, p)
		peers = append(peers, Peer{ID: id, Address: p.Address()})
	}
	l.coordinator, err = startCoordinator(listener, peers, directory, log)
	if err != nil {
		listener.Close()
		l.Close()
		return nil, err
	}
	return l, nil
}

func (l *lab) Close() {
	if l.coordinator != nil {
		l.coordinator.Close()
	}
	for _, p := range l.participants {
		p.Close()
	}
}

func (l *lab) verify(txID int, expected string) error {
	for _, p := range l.participants {
		if got := p.State(txID); got != expected {
			return fmt.Errorf("Tx %d: P%d has %s, want %s", txID, p.id, got, expected)
		}
	}
	return nil
}

func runDemo(directory string, out io.Writer) error {
	logger := &traceLogger{out: out}
	l, err := startLab(directory, logger.printf)
	if err != nil {
		return err
	}
	defer l.Close()
	logger.printf("[SYSTEM] Stable WAL directory: %s", directory)

	// 101: P3 chủ động bỏ phiếu NO; coordinator phải ABORT toàn bộ.
	logger.printf("[SYSTEM] SCENARIO 1: inject P3 lock conflict for Tx 101")
	l.participants[2].InjectAbort(101)
	result, err := l.coordinator.Execute(101, nil)
	if err != nil {
		return err
	}
	if result.Decision != globalAbort || !result.AllACK() {
		return fmt.Errorf("Tx 101 did not abort with all ACKs")
	}
	if err := l.verify(101, globalAbort); err != nil {
		return err
	}
	logger.printf("[SYSTEM] Tx 101 Aborted Cleanly.")

	// 102: đường chạy thành công, cả ba phải ghi YES trước global COMMIT.
	logger.printf("[SYSTEM] SCENARIO 2: unanimous YES for Tx 102")
	result, err = l.coordinator.Execute(102, nil)
	if err != nil {
		return err
	}
	if result.Decision != globalCommit || !result.AllACK() {
		return fmt.Errorf("Tx 102 did not commit with all ACKs")
	}
	if err := l.verify(102, globalCommit); err != nil {
		return err
	}
	logger.printf("[SYSTEM] Tx 102 Committed Cleanly.")

	// 103: P2 đã Sync YES, nhưng crash trước khi nhận global decision.
	logger.printf("[SYSTEM] SCENARIO 3: crash/reboot P2 while PREPARED in Tx 103")
	oldP2 := l.participants[1]
	p2Address := oldP2.Address()
	result, err = l.coordinator.Execute(103, func() {
		oldP2.Close()
		logger.printf("[SYSTEM] P2 terminated after durable VOTE_COMMIT, before GLOBAL_COMMIT delivery.")
	})
	if err != nil {
		return err
	}
	if result.Decision != globalCommit || result.ACKs[2] || oldP2.State(103) != voteCommit {
		return fmt.Errorf("Tx 103 fault injection did not leave P2 prepared")
	}
	logger.printf("[SYSTEM] Reboot P2: create a NEW object from the SAME WAL file.")
	newP2, err := startParticipant(2, p2Address, directory, l.coordinator.server.listener.Addr().String(), logger.printf)
	if err != nil {
		return err
	}
	l.participants[1] = newP2
	if newP2.State(103) != voteCommit {
		return fmt.Errorf("P2 failed to replay PREPARED state from disk")
	}
	if err := newP2.Recover(); err != nil {
		return err
	}
	// Gửi lại quyết định để thu đủ ACK; P1/P3 và P2 xử lý duplicate an toàn.
	result, err = l.coordinator.Execute(103, nil)
	if err != nil {
		return err
	}
	if !result.AllACK() {
		return fmt.Errorf("Tx 103 still missing ACK after reboot")
	}
	if err := l.verify(103, globalCommit); err != nil {
		return err
	}
	logger.printf("[SYSTEM] Tx 103 Recovered Cleanly. All participants GLOBAL_COMMIT.")
	l.Close()
	logger.printf("[VERIFY] PASS: ABORT, COMMIT, durable WAL and participant reboot recovery.")
	return logger.failure()
}

// Khởi động lại cả 4 server từ một thư mục WAL đã có.
func runRecovery(directory string, out io.Writer) error {
	logger := &traceLogger{out: out}
	l, err := startLab(directory, logger.printf)
	if err != nil {
		return err
	}
	defer l.Close()
	for _, p := range l.participants {
		if err := p.Recover(); err != nil {
			return err
		}
	}
	if err := l.coordinator.Redeliver(); err != nil {
		return err
	}
	l.Close()
	logger.printf("[VERIFY] PASS: reboot replay and decision redelivery completed.")
	return logger.failure()
}
