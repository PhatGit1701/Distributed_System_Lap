package main

import (
	"fmt"
	"net"
	"path/filepath"
	"slices"
	"sort"
	"sync"
)

type transaction struct {
	participants []int
	decision     string // Rỗng khi mới chỉ có START_2PC.
}

type Outcome struct {
	TxID     int
	Decision string
	Votes    map[int]string
	ACKs     map[int]bool
}

func (o Outcome) AllACK() bool {
	if len(o.ACKs) != 3 {
		return false
	}
	for _, received := range o.ACKs {
		if !received {
			return false
		}
	}
	return true
}

type Coordinator struct {
	wal       *WAL
	server    *socketServer
	peers     []Peer
	log       logFunc
	executeMu sync.Mutex // Demo chạy tuần tự các transaction.
	mu        sync.Mutex // Không giữ mu trong lúc chờ mạng.
	txs       map[int]*transaction
	failed    error
	closeOnce sync.Once
}

func startCoordinator(listener net.Listener, peers []Peer, directory string, log logFunc) (*Coordinator, error) {
	peers = slices.Clone(peers)
	sort.Slice(peers, func(i, j int) bool { return peers[i].ID < peers[j].ID })
	if len(peers) != 3 || peers[0].ID != 1 || peers[1].ID != 2 || peers[2].ID != 3 {
		return nil, fmt.Errorf("lab requires exactly participants P1, P2, P3")
	}
	wal, records, err := openWAL(filepath.Join(directory, "coordinator.wal"))
	if err != nil {
		return nil, err
	}
	c := &Coordinator{wal: wal, peers: peers, log: log, txs: make(map[int]*transaction)}
	for _, record := range records {
		if err := c.replay(record); err != nil {
			wal.Close()
			return nil, err
		}
	}
	// Không có decision bền vững => coordinator được chọn ABORT khi reboot.
	// COMMIT đã được Sync thì tuyệt đối không được đổi sang ABORT.
	for _, txID := range c.sortedTransactions() {
		if c.txs[txID].decision == "" {
			if err := c.saveDecision(txID, globalAbort); err != nil {
				wal.Close()
				return nil, err
			}
			log("[COORD] Recovery Tx %d: START without decision -> Log GLOBAL_ABORT (Sync)", txID)
		}
	}
	c.server = newServer(listener, c.handle)
	log("[COORD] Socket server %s | recovered %d WAL records", listener.Addr(), len(records))
	return c, nil
}

func (c *Coordinator) replay(record Record) error {
	tx := c.txs[record.TxID]
	if record.Event == start2PC {
		if tx != nil || !slices.Equal(record.Participants, []int{1, 2, 3}) {
			return fmt.Errorf("coordinator WAL: invalid START for Tx %d", record.TxID)
		}
		c.txs[record.TxID] = &transaction{participants: slices.Clone(record.Participants)}
		return nil
	}
	if !isDecision(record.Event) || tx == nil || tx.decision != "" {
		return fmt.Errorf("coordinator WAL: invalid decision for Tx %d", record.TxID)
	}
	tx.decision = record.Event
	return nil
}

func (c *Coordinator) sortedTransactions() []int {
	var ids []int
	for txID := range c.txs {
		ids = append(ids, txID)
	}
	sort.Ints(ids)
	return ids
}

func (c *Coordinator) handle(message Message) Message {
	c.mu.Lock()
	defer c.mu.Unlock()
	if message.Type != getDecision || message.TxID <= 0 || c.failed != nil {
		return Message{Type: protocolErr, TxID: message.TxID, Detail: "invalid request or coordinator storage failed"}
	}
	decision := unknown
	if tx := c.txs[message.TxID]; tx != nil && tx.decision != "" {
		decision = tx.decision
	}
	c.log("[COORD] Recovery query Tx %d -> %s", message.TxID, decision)
	return Message{Type: decision, TxID: message.TxID}
}

// Caller giữ c.mu, hoặc đang khởi tạo trước khi server được start.
func (c *Coordinator) saveDecision(txID int, decision string) error {
	if err := c.wal.Append(Record{TxID: txID, Event: decision}); err != nil {
		c.failed = err
		return err
	}
	c.txs[txID].decision = decision
	return nil
}

type peerReply struct {
	peer    Peer
	message Message
	err     error
}

func (c *Coordinator) multicast(kind string, txID int) []peerReply {
	results := make(chan peerReply, len(c.peers))
	for _, peer := range c.peers {
		go func(peer Peer) {
			message, err := call(peer.Address, Message{Type: kind, TxID: txID})
			results <- peerReply{peer: peer, message: message, err: err}
		}(peer)
	}
	replies := make([]peerReply, 0, len(c.peers))
	for range c.peers {
		replies = append(replies, <-results)
	}
	return replies
}

// beforeBroadcast chỉ là điểm tiêm lỗi cho demo/test, chạy SAU decision+Sync.
// Nó không được sửa votes hay decision của coordinator.
func (c *Coordinator) Execute(txID int, beforeBroadcast func()) (Outcome, error) {
	c.executeMu.Lock()
	defer c.executeMu.Unlock()
	outcome := Outcome{TxID: txID, Votes: make(map[int]string), ACKs: make(map[int]bool)}
	if txID <= 0 {
		return outcome, fmt.Errorf("tx_id must be positive")
	}
	c.mu.Lock()
	if c.failed != nil {
		c.mu.Unlock()
		return outcome, c.failed
	}
	tx := c.txs[txID]
	if tx != nil {
		outcome.Decision = tx.decision
		c.mu.Unlock()
		if outcome.Decision == "" {
			return outcome, fmt.Errorf("Tx %d in doubt: restart coordinator to recover", txID)
		}
		c.log("[COORD] Tx %d Retry -> keep durable %s", txID, outcome.Decision)
		c.deliver(&outcome)
		return outcome, nil
	}
	if err := c.wal.Append(Record{TxID: txID, Event: start2PC, Participants: []int{1, 2, 3}}); err != nil {
		c.failed = err
		c.mu.Unlock()
		return outcome, err
	}
	c.txs[txID] = &transaction{participants: []int{1, 2, 3}}
	c.mu.Unlock()
	c.log("[COORD] Tx %d Started -> Log START_2PC (Sync)", txID)
	c.log("[COORD] Tx %d Multicast VOTE_REQUEST", txID)

	decision := globalCommit
	for _, result := range c.multicast(voteRequest, txID) {
		vote := result.message.Type
		if result.err != nil || result.message.TxID != txID || (vote != voteCommit && vote != voteAbort) {
			vote = "TIMEOUT_OR_ERROR"
		}
		outcome.Votes[result.peer.ID] = vote
		c.log("[COORD] Tx %d Recv %s from P%d", txID, vote, result.peer.ID)
		if vote != voteCommit {
			decision = globalAbort
		}
	}
	c.mu.Lock()
	err := c.saveDecision(txID, decision)
	c.mu.Unlock()
	if err != nil {
		return outcome, err // Không gửi bất kỳ decision chưa Sync nào.
	}
	outcome.Decision = decision
	c.log("[COORD] Tx %d Log %s (Sync)", txID, decision)
	if beforeBroadcast != nil {
		beforeBroadcast()
	}
	c.deliver(&outcome)
	return outcome, nil
}

func (c *Coordinator) deliver(outcome *Outcome) {
	c.log("[COORD] Tx %d Multicast %s", outcome.TxID, outcome.Decision)
	for _, result := range c.multicast(outcome.Decision, outcome.TxID) {
		ok := result.err == nil && result.message.Type == ack && result.message.TxID == outcome.TxID
		outcome.ACKs[result.peer.ID] = ok
		if ok {
			c.log("[COORD] Tx %d Recv ACK from P%d", outcome.TxID, result.peer.ID)
		} else {
			c.log("[COORD] Tx %d Missing ACK from P%d -> decision remains %s; retry after recovery",
				outcome.TxID, result.peer.ID, outcome.Decision)
		}
	}
}

// Sau reboot coordinator gửi lại mọi quyết định đã có. Duplicate là an toàn.
func (c *Coordinator) Redeliver() error {
	c.mu.Lock()
	ids := c.sortedTransactions()
	c.mu.Unlock()
	for _, txID := range ids {
		outcome, err := c.Execute(txID, nil)
		if err != nil {
			return err
		}
		if !outcome.AllACK() {
			return fmt.Errorf("Tx %d still has missing ACKs", txID)
		}
	}
	return nil
}

func (c *Coordinator) Close() {
	c.closeOnce.Do(func() {
		// Không đóng WAL giữa một Execute đang chạy.
		c.executeMu.Lock()
		defer c.executeMu.Unlock()
		c.server.Close()
		c.wal.Close()
	})
}
