package main

import (
	"encoding/json"
	"net"
	"time"
)

// Một TCP connection chỉ mang một request và một response.
func call(address string, message Message, timeout time.Duration) (Message, error) {
	conn, err := net.DialTimeout("tcp", address, timeout)
	if err != nil {
		return Message{}, err
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		return Message{}, err
	}
	if err := json.NewEncoder(conn).Encode(message); err != nil {
		return Message{}, err
	}
	var reply Message
	err = json.NewDecoder(conn).Decode(&reply)
	return reply, err
}

func (n *Node) acceptConnections() {
	for {
		conn, err := n.listener.Accept()
		if err != nil {
			return // Stop() đóng listener để đánh thức Accept().
		}
		n.workers.Add(1)
		go func() {
			defer n.workers.Done()
			n.handleConnection(conn)
		}()
	}
}

func (n *Node) handleConnection(conn net.Conn) {
	defer conn.Close()
	if conn.SetDeadline(time.Now().Add(n.timing.RPC)) != nil {
		return
	}
	var message Message
	if json.NewDecoder(conn).Decode(&message) != nil {
		return
	}
	req := request{message: message, reply: make(chan Message, 1)}
	select {
	case n.inbox <- req:
	case <-n.done:
		return
	}
	select {
	case reply := <-req.reply:
		_ = json.NewEncoder(conn).Encode(reply)
	case <-n.done:
	}
}

// Không gọi TCP đồng bộ từ vòng lặp bầu cử: hai node có thể đang hỏi nhau.
// Worker trả kết quả qua channel, tuyệt đối không tự sửa trạng thái node.
func (n *Node) send(target int, kind string, round uint64) {
	n.workers.Add(1)
	go func() {
		defer n.workers.Done()
		reply, err := call(n.peers[target], Message{Type: kind, From: n.id}, n.timing.RPC)
		expected := msgOK
		if kind == msgPing {
			expected = msgAlive
		}
		result := rpcResult{
			kind: kind, target: target, round: round,
			ok: err == nil && reply.Type == expected && reply.From == target,
		}
		select {
		case n.results <- result:
		case <-n.done:
		}
	}()
}
