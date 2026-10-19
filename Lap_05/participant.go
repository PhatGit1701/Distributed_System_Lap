package main

import (
	"fmt"
	"net"
	"path/filepath"
	"sort"
	"sync"
)

type Participant struct {
	id          int
	coordinator string
	wal         *WAL
	server      *socketServer
	log         logFunc
	mu          sync.Mutex
	states      map[int]string
	lockOwner   int // Một tài nguyên mô phỏng; 0 = chưa bị khóa.
	abortTx     map[int]bool
	failed      error
	closeOnce   sync.Once
}

func startParticipant(id int, address, directory, coordinator string, log logFunc) (*Participant, error) {
	wal, records, err := openWAL(filepath.Join(directory, fmt.Sprintf("participant-%d.wal", id)))
	if err != nil {
		return nil, err
	}
	p := &Participant{
		id: id, wal: wal, coordinator: coordinator, log: log,
		states: make(map[int]string), abortTx: make(map[int]bool),
	}
	// Phục hồi cả trạng thái giao dịch và khóa PREPARED từ ổ đĩa.
	for _, record := range records {
		if err := p.replay(record); err != nil {
			wal.Close()
			return nil, err
		}
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		wal.Close()
		return nil, err
	}
	p.server = newServer(listener, p.handle)
	log("[P%d] Socket server %s | recovered %d WAL records", id, listener.Addr(), len(records))
	return p, nil
}

func (p *Participant) replay(record Record) error {
	old := p.states[record.TxID]
	switch record.Event {
	case voteCommit, voteAbort:
		if old != "" || (record.Event == voteCommit && p.lockOwner != 0) {
			return fmt.Errorf("P%d WAL: invalid vote transition for Tx %d", p.id, record.TxID)
		}
		if record.Event == voteCommit {
			p.lockOwner = record.TxID
		}
	case globalCommit:
		if old != voteCommit {
			return fmt.Errorf("P%d WAL: COMMIT without durable YES for Tx %d", p.id, record.TxID)
		}
	case globalAbort:
		if isDecision(old) {
			return fmt.Errorf("P%d WAL: duplicate/conflicting decision for Tx %d", p.id, record.TxID)
		}
	default:
		return fmt.Errorf("P%d WAL: unknown event %s", p.id, record.Event)
	}
	p.states[record.TxID] = record.Event
	if isDecision(record.Event) && p.lockOwner == record.TxID {
		p.lockOwner = 0
	}
	return nil
}

func (p *Participant) Address() string {
	return p.server.listener.Addr().String()
}

func (p *Participant) State(txID int) string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.states[txID]
}

// Fault injection chỉ đổi hành vi bỏ phiếu, không sửa log hay kết quả.
func (p *Participant) InjectAbort(txID int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.abortTx[txID] = true
}

func (p *Participant) handle(message Message) Message {
	p.mu.Lock()
	defer p.mu.Unlock()
	reply := Message{Type: protocolErr, TxID: message.TxID}
	if message.TxID <= 0 {
		reply.Detail = "tx_id must be positive"
		return reply
	}
	if p.failed != nil {
		reply.Detail = p.failed.Error()
		return reply
	}
	switch message.Type {
	case voteRequest:
		return p.vote(message.TxID)
	case globalCommit, globalAbort:
		return p.decide(message.TxID, message.Type)
	default:
		reply.Detail = "unsupported participant request"
		return reply
	}
}

// Caller giữ p.mu. Ghi và Sync trước, rồi mới công bố trạng thái/phiếu.
func (p *Participant) vote(txID int) Message {
	state := p.states[txID]
	if state != "" {
		// Retry phải trả cùng vote, không ghi hay thực hiện lần hai.
		vote := voteAbort
		if state == voteCommit || state == globalCommit {
			vote = voteCommit
		}
		return Message{Type: vote, TxID: txID}
	}
	vote := voteCommit
	if p.abortTx[txID] || (p.lockOwner != 0 && p.lockOwner != txID) {
		vote = voteAbort
		p.log("[P%d] Tx %d Lock Conflict! (injected or resource busy)", p.id, txID)
	}
	if err := p.wal.Append(Record{TxID: txID, Event: vote}); err != nil {
		p.failed = err
		return Message{Type: protocolErr, TxID: txID, Detail: err.Error()}
	}
	p.states[txID] = vote
	if vote == voteCommit {
		p.lockOwner = txID
	}
	p.log("[P%d] Tx %d Log %s (Sync) -> Reply %s", p.id, txID, vote, vote)
	return Message{Type: vote, TxID: txID}
}

// Commit/rollback ở lab là chuyển trạng thái bền vững, không gọi database.
func (p *Participant) decide(txID int, decision string) Message {
	old := p.states[txID]
	if old == decision {
		p.log("[P%d] Tx %d Duplicate %s -> Reply ACK (no second apply)", p.id, txID, decision)
		return Message{Type: ack, TxID: txID}
	}
	if isDecision(old) || (decision == globalCommit && old != voteCommit) {
		return Message{Type: protocolErr, TxID: txID, Detail: "conflicting decision or COMMIT without YES"}
	}
	if err := p.wal.Append(Record{TxID: txID, Event: decision}); err != nil {
		p.failed = err
		return Message{Type: protocolErr, TxID: txID, Detail: err.Error()}
	}
	p.states[txID] = decision
	if p.lockOwner == txID {
		p.lockOwner = 0
	}
	action := "Rollback"
	if decision == globalCommit {
		action = "Commit"
	}
	p.log("[P%d] Tx %d Recv %s -> Log+Sync -> %s -> Reply ACK", p.id, txID, decision, action)
	return Message{Type: ack, TxID: txID}
}

// Sau reboot, PREPARED không được tự abort/commit vì timeout.
// Coordinator trả UNKNOWN hoặc không liên lạc được => vẫn giữ vote và khóa.
func (p *Participant) Recover() error {
	p.mu.Lock()
	var pending []int
	for txID, state := range p.states {
		if !isDecision(state) {
			pending = append(pending, txID)
		}
	}
	p.mu.Unlock()
	sort.Ints(pending)
	for _, txID := range pending {
		p.log("[P%d] Recovery Tx %d: %s -> ask coordinator via TCP", p.id, txID, p.State(txID))
		reply, err := call(p.coordinator, Message{Type: getDecision, TxID: txID})
		if err != nil || reply.TxID != txID || !isDecision(reply.Type) {
			p.log("[P%d] Recovery Tx %d: decision unavailable -> remain in doubt (blocking)", p.id, txID)
			return fmt.Errorf("P%d Tx %d still has no durable global decision", p.id, txID)
		}
		response := p.handle(Message{Type: reply.Type, TxID: txID})
		if response.Type != ack {
			return fmt.Errorf("P%d recovery: %s", p.id, response.Detail)
		}
	}
	return nil
}

func (p *Participant) Close() {
	p.closeOnce.Do(func() {
		p.server.Close()
		p.wal.Close()
	})
}
