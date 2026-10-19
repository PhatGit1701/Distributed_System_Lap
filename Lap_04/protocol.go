package main

import "time"

// Message là dữ liệu thực sự đi qua TCP, được mã hóa thành JSON.
type Message struct {
	Type string `json:"type"`
	From int    `json:"from"`
}

const (
	msgPing        = "PING"
	msgAlive       = "ALIVE"
	msgElection    = "ELECTION"
	msgOK          = "OK"
	msgCoordinator = "COORDINATOR"
	msgReject      = "REJECT"
)

type phase string

const (
	idle               phase = "IDLE"
	waitingOK          phase = "WAIT_OK"
	waitingCoordinator phase = "WAIT_COORDINATOR"
)

// Có ba khoảng chờ khác nhau: TCP, OK, và thông báo coordinator.
type Timing struct {
	RPC         time.Duration
	Heartbeat   time.Duration
	Election    time.Duration
	Coordinator time.Duration
	Tick        time.Duration
}

func defaultTiming() Timing {
	return Timing{
		RPC:         200 * time.Millisecond,
		Heartbeat:   300 * time.Millisecond,
		Election:    600 * time.Millisecond,
		Coordinator: 1500 * time.Millisecond,
		Tick:        20 * time.Millisecond,
	}
}

// request và rpcResult chỉ dùng NỘI BỘ node, không phải kênh giữa các node.
type request struct {
	message Message
	reply   chan Message
}

type rpcResult struct {
	kind   string
	target int
	round  uint64
	ok     bool
}

type logFunc func(string, ...any)
