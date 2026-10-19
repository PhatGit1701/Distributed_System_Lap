package main

import "time"

const (
	voteRequest  = "VOTE_REQUEST"
	voteCommit   = "VOTE_COMMIT"
	voteAbort    = "VOTE_ABORT"
	globalCommit = "GLOBAL_COMMIT"
	globalAbort  = "GLOBAL_ABORT"
	getDecision  = "GET_DECISION"
	start2PC     = "START_2PC"
	ack          = "ACK"
	unknown      = "UNKNOWN"
	protocolErr  = "ERROR"
)

const rpcTimeout = 800 * time.Millisecond

// Một TCP request và response đều dùng cấu trúc JSON này.
type Message struct {
	Type   string `json:"type"`
	TxID   int    `json:"tx_id"`
	Detail string `json:"detail,omitempty"`
}

type Peer struct {
	ID      int
	Address string
}

type logFunc func(string, ...any)

func isDecision(event string) bool {
	return event == globalCommit || event == globalAbort
}
