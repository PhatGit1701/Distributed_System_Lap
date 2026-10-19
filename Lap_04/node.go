package main

import (
	"net"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// Node mô phỏng một tiến trình. Các node chỉ trao đổi thông điệp qua TCP.
type Node struct {
	id       int
	ids      []int
	peers    map[int]string // Bản sao riêng, chỉ đọc sau khi tạo.
	listener net.Listener
	timing   Timing
	log      logFunc

	inbox   chan request
	results chan rpcResult
	done    chan struct{}
	stop    sync.Once
	workers sync.WaitGroup

	// Main chỉ đọc giá trị này để xác minh. Chỉ run() được ghi.
	publishedLeader atomic.Int32

	// Các biến dưới đây chỉ được dùng bởi goroutine run().
	state       phase
	round       uint64
	higherOK    bool
	deadline    time.Time
	nextPing    time.Time
	pingPending bool
}

func newNode(id int, listener net.Listener, peers map[int]string, leader int, timing Timing, log logFunc) *Node {
	n := &Node{
		id: id, listener: listener, timing: timing, log: log,
		peers: make(map[int]string, len(peers)),
		inbox: make(chan request), results: make(chan rpcResult, 32),
		done: make(chan struct{}), state: idle,
		nextPing: time.Now().Add(timing.Heartbeat),
	}
	for peer, address := range peers {
		n.peers[peer] = address
		n.ids = append(n.ids, peer)
	}
	sort.Ints(n.ids) // Thứ tự gửi trong log dễ đọc hơn.
	n.publishedLeader.Store(int32(leader))
	return n
}

func (n *Node) Leader() int {
	return int(n.publishedLeader.Load())
}

func (n *Node) Start() {
	n.workers.Add(2)
	go func() { defer n.workers.Done(); n.acceptConnections() }()
	go func() { defer n.workers.Done(); n.run() }()
}

// Go không cho kill một goroutine tùy ý. Dừng hợp tác và đóng endpoint TCP
// mô phỏng crash-stop: node không còn xử lý hay trả lời thông điệp.
func (n *Node) Stop() {
	n.stop.Do(func() {
		close(n.done)
		_ = n.listener.Close()
	})
	n.workers.Wait()
}

func (n *Node) run() {
	ticker := time.NewTicker(n.timing.Tick)
	defer ticker.Stop()
	for {
		select {
		case <-n.done:
			return
		case req := <-n.inbox:
			req.reply <- n.onMessage(req.message)
		case result := <-n.results:
			n.onResult(result)
		case now := <-ticker.C:
			n.onTick(now)
		}
	}
}

// Bước 1 của Bully: hỏi tất cả node có ID cao hơn.
func (n *Node) startElection(reason string) {
	if n.state != idle {
		return // Đã bầu thì không mở thêm một vòng song song trong cùng node.
	}
	n.round++
	n.state = waitingOK
	n.higherOK = false
	n.pingPending = false
	n.publishedLeader.Store(0) // 0 nghĩa là chưa biết leader.
	n.deadline = time.Now().Add(n.timing.Election)
	n.log("[P%d] Initiating Election... (%s)", n.id, reason)
	for _, peer := range n.ids {
		if peer > n.id {
			n.log("[P%d] Send ELECTION -> P%d", n.id, peer)
			n.send(peer, msgElection, n.round)
		}
	}
}

func (n *Node) onMessage(message Message) Message {
	reply := Message{Type: msgReject, From: n.id}
	if _, known := n.peers[message.From]; !known || message.From == n.id {
		return reply
	}
	switch message.Type {
	case msgPing:
		if n.Leader() == n.id {
			reply.Type = msgAlive
		}
	case msgElection:
		if message.From < n.id {
			n.log("[P%d] Recv ELECTION from P%d -> Reply OK", n.id, message.From)
			reply.Type = msgOK
			n.startElection("received ELECTION from a lower ID")
		}
	case msgCoordinator:
		if message.From > n.id {
			n.round++ // Hủy hiệu lực mọi kết quả PING/ELECTION cũ.
			n.publishedLeader.Store(int32(message.From))
			n.state = idle
			n.pingPending = false
			n.nextPing = time.Now().Add(n.timing.Heartbeat)
			reply.Type = msgOK
			n.log("[P%d] Recv COORDINATOR from P%d -> Leader P%d", n.id, message.From, message.From)
		} else {
			// Một node ID thấp hơn tuyên bố thắng: áp dụng ưu tiên Bully.
			n.startElection("rejected a lower-ID coordinator")
		}
	}
	return reply
}

func (n *Node) onResult(result rpcResult) {
	if result.round != n.round {
		return // Phản hồi đến muộn không được ảnh hưởng vòng bầu cử mới.
	}
	switch result.kind {
	case msgPing:
		n.pingPending = false
		if n.state == idle && n.Leader() == result.target && !result.ok {
			n.log("[P%d] Timeout detected! Leader P%d unreachable.", n.id, result.target)
			n.startElection("leader heartbeat failed")
		}
	case msgElection:
		if n.state == waitingOK && result.ok {
			n.higherOK = true
			n.log("[P%d] Recv OK from P%d", n.id, result.target)
		}
	case msgCoordinator:
		if !result.ok {
			n.log("[P%d] COORDINATOR delivery to P%d failed", n.id, result.target)
		}
	}
}

func (n *Node) onTick(now time.Time) {
	if n.state == idle {
		if n.Leader() != n.id && !n.pingPending && !now.Before(n.nextPing) {
			n.pingPending = true
			n.nextPing = now.Add(n.timing.Heartbeat)
			n.send(n.Leader(), msgPing, n.round)
		}
		return
	}
	if now.Before(n.deadline) {
		return
	}
	if n.state == waitingOK {
		if !n.higherOK {
			n.becomeLeader()
		} else {
			n.state = waitingCoordinator
			n.deadline = now.Add(n.timing.Coordinator)
			n.log("[P%d] Higher ID replied OK -> waiting for COORDINATOR", n.id)
		}
		return
	}
	// Có OK nhưng không có coordinator: node cao hơn có thể vừa crash.
	n.log("[P%d] COORDINATOR timeout -> retry election", n.id)
	n.state = idle
	n.startElection("no COORDINATOR received")
}

// Bước cuối: không có OK từ node cao hơn => tự thắng, thông báo node thấp hơn.
func (n *Node) becomeLeader() {
	n.state = idle
	n.publishedLeader.Store(int32(n.id))
	n.log("[P%d] Timeout waiting for higher IDs -> NEW LEADER!", n.id)
	for _, peer := range n.ids {
		if peer < n.id {
			n.log("[P%d] Broadcast COORDINATOR victory -> P%d", n.id, peer)
			n.send(peer, msgCoordinator, n.round)
		}
	}
}
