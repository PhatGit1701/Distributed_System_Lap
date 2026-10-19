package main

import (
	"bytes"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func testLab(t *testing.T) *lab {
	t.Helper()
	l, err := startLab(t.TempDir(), func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(l.Close)
	return l
}

func sendTo(t *testing.T, p *Participant, kind string, txID int, expected string) {
	t.Helper()
	reply, err := call(p.Address(), Message{Type: kind, TxID: txID})
	if err != nil || reply.Type != expected || reply.TxID != txID {
		t.Fatalf("%s Tx %d: got %+v, %v; want %s", kind, txID, reply, err, expected)
	}
}

func readRecords(t *testing.T, path string) []Record {
	t.Helper()
	wal, records, err := openWAL(path)
	if err != nil {
		t.Fatal(err)
	}
	wal.Close()
	return records
}

func seedWAL(t *testing.T, path string, records ...Record) {
	t.Helper()
	wal, _, err := openWAL(path)
	if err != nil {
		t.Fatal(err)
	}
	defer wal.Close()
	for _, record := range records {
		if err := wal.Append(record); err != nil {
			t.Fatal(err)
		}
	}
}

func TestDemoAbortCommitAndRealReboot(t *testing.T) {
	directory := t.TempDir()
	var trace bytes.Buffer
	if err := runDemo(directory, &trace); err != nil {
		t.Fatal(err)
	}
	for _, expected := range []string{
		"Tx 101 Aborted Cleanly", "Tx 102 Committed Cleanly",
		"Log VOTE_ABORT (Sync)", "P2 terminated", "Recovery Tx 103: VOTE_COMMIT",
		"Recovery query Tx 103 -> GLOBAL_COMMIT", "Duplicate GLOBAL_COMMIT",
		"Tx 103 Recovered Cleanly", "[VERIFY] PASS",
	} {
		if !strings.Contains(trace.String(), expected) {
			t.Errorf("missing trace: %s", expected)
		}
	}
	records := readRecords(t, filepath.Join(directory, "participant-2.wal"))
	var events []string
	for _, record := range records {
		if record.TxID == 103 {
			events = append(events, record.Event)
		}
	}
	if strings.Join(events, ",") != voteCommit+","+globalCommit {
		t.Fatalf("reboot/duplicate must not apply twice: %v", events)
	}
}

func TestMissingVoteAbortsButMissingACKDoesNotChangeDecision(t *testing.T) {
	l := testLab(t)
	old := l.participants[2]
	address := old.Address()
	old.Close()
	outcome, err := l.coordinator.Execute(201, nil)
	if err != nil || outcome.Decision != globalAbort || outcome.AllACK() {
		t.Fatalf("missing vote must abort, with missing ACK recorded: %+v, %v", outcome, err)
	}
	rebooted, err := startParticipant(3, address, l.directory,
		l.coordinator.server.listener.Addr().String(), l.log)
	if err != nil {
		t.Fatal(err)
	}
	l.participants[2] = rebooted
	outcome, err = l.coordinator.Execute(201, nil)
	if err != nil || outcome.Decision != globalAbort || !outcome.AllACK() {
		t.Fatalf("retry must redeliver ABORT: %+v, %v", outcome, err)
	}
	if err := l.verify(201, globalAbort); err != nil {
		t.Fatal(err)
	}
}

func TestPreparedParticipantRebootKeepsLockWhenDecisionUnknown(t *testing.T) {
	l := testLab(t)
	old := l.participants[0]
	sendTo(t, old, voteRequest, 301, voteCommit)
	address := old.Address()
	old.Close()
	rebooted, err := startParticipant(1, address, l.directory,
		l.coordinator.server.listener.Addr().String(), l.log)
	if err != nil {
		t.Fatal(err)
	}
	l.participants[0] = rebooted
	if err := rebooted.Recover(); err == nil {
		t.Fatal("UNKNOWN must keep transaction in doubt, not silently abort")
	}
	if rebooted.State(301) != voteCommit {
		t.Fatal("prepared state was lost on reboot")
	}
	sendTo(t, rebooted, voteRequest, 302, voteAbort) // Khóa 301 được phục hồi.
	sendTo(t, rebooted, globalAbort, 301, ack)
	sendTo(t, rebooted, voteRequest, 303, voteCommit) // Rollback giải phóng khóa.
}

func TestCoordinatorRebootAbortsOnlyTransactionsWithoutDecision(t *testing.T) {
	for _, committed := range []bool{false, true} {
		t.Run(fmt.Sprintf("durable_commit_%v", committed), func(t *testing.T) {
			directory := t.TempDir()
			records := []Record{{TxID: 401, Event: start2PC, Participants: []int{1, 2, 3}}}
			want := globalAbort
			if committed {
				records = append(records, Record{TxID: 401, Event: globalCommit})
				want = globalCommit
			}
			seedWAL(t, filepath.Join(directory, "coordinator.wal"), records...)
			for id := 1; id <= 3; id++ {
				seedWAL(t, filepath.Join(directory, fmt.Sprintf("participant-%d.wal", id)),
					Record{TxID: 401, Event: voteCommit})
			}
			l, err := startLab(directory, func(string, ...any) {})
			if err != nil {
				t.Fatal(err)
			}
			defer l.Close()
			for _, p := range l.participants {
				if err := p.Recover(); err != nil {
					t.Fatal(err)
				}
			}
			if err := l.coordinator.Redeliver(); err != nil {
				t.Fatal(err)
			}
			if err := l.verify(401, want); err != nil {
				t.Fatal(err)
			}
			l.Close()
			got := readRecords(t, filepath.Join(directory, "coordinator.wal"))
			if len(got) != 2 || got[1].Event != want {
				t.Fatalf("recovery changed durable decision or repeated log: %+v", got)
			}
		})
	}
}

func TestDuplicateVotesAndDecisionsAreIdempotent(t *testing.T) {
	l := testLab(t)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			reply, err := call(l.participants[0].Address(), Message{Type: voteRequest, TxID: 501})
			if err != nil || reply.Type != voteCommit {
				t.Errorf("concurrent duplicate vote: %+v, %v", reply, err)
			}
		}()
	}
	wg.Wait()
	for i := 0; i < 2; i++ {
		outcome, err := l.coordinator.Execute(501, nil)
		if err != nil || outcome.Decision != globalCommit || !outcome.AllACK() {
			t.Fatalf("commit/retry failed: %+v, %v", outcome, err)
		}
	}
	sendTo(t, l.participants[0], globalAbort, 501, protocolErr)
	if l.participants[0].State(501) != globalCommit {
		t.Fatal("conflicting ABORT changed a committed transaction")
	}
	l.Close()
	if got := readRecords(t, filepath.Join(l.directory, "participant-1.wal")); len(got) != 2 {
		t.Fatalf("duplicate requests added duplicate WAL records: %+v", got)
	}
}

func TestParticipantRejectsCommitWithoutDurableYES(t *testing.T) {
	l := testLab(t)
	p := l.participants[0]
	sendTo(t, p, globalCommit, 601, protocolErr)
	p.InjectAbort(601)
	sendTo(t, p, voteRequest, 601, voteAbort)
	sendTo(t, p, globalCommit, 601, protocolErr)
	if p.State(601) != voteAbort {
		t.Fatal("participant committed despite voting NO")
	}
}

func TestWALFailureNeverRepliesYES(t *testing.T) {
	l := testLab(t)
	p := l.participants[0]
	p.wal.file.Close() // Tiêm lỗi ghi ổ đĩa.
	sendTo(t, p, voteRequest, 701, protocolErr)
	if p.State(701) != "" {
		t.Fatal("state changed although WAL write failed")
	}
	sendTo(t, p, voteRequest, 702, protocolErr) // Fail-stop sau lỗi lưu trữ.
}

func TestWALTornTailRecoveryAndCorruptRecordRejection(t *testing.T) {
	path := filepath.Join(t.TempDir(), "test.wal")
	seedWAL(t, path, Record{TxID: 801, Event: voteCommit})
	file, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	file.WriteString("{partial")
	file.Close()
	if got := readRecords(t, path); len(got) != 1 || got[0].Event != voteCommit {
		t.Fatalf("valid prefix lost after partial tail: %+v", got)
	}
	file, err = os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	file.WriteString("{invalid-json}\n")
	file.Close()
	if wal, _, err := openWAL(path); err == nil {
		wal.Close()
		t.Fatal("complete corrupt record was silently ignored")
	}
}

func TestResumeExistingWALs(t *testing.T) {
	directory := t.TempDir()
	if err := runDemo(directory, io.Discard); err != nil {
		t.Fatal(err)
	}
	if err := runRecovery(directory, io.Discard); err != nil {
		t.Fatal(err)
	}
}

func TestTCPServerStopsAcceptingAfterClose(t *testing.T) {
	l := testLab(t)
	address := l.participants[0].Address()
	l.participants[0].Close()
	conn, err := net.DialTimeout("tcp", address, rpcTimeout)
	if err == nil {
		conn.Close()
		t.Fatal("closed participant still accepts connections")
	}
}
