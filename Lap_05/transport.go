package main

import (
	"encoding/json"
	"io"
	"net"
	"sync"
	"time"
)

func call(address string, message Message) (Message, error) {
	conn, err := net.DialTimeout("tcp", address, rpcTimeout)
	if err != nil {
		return Message{}, err
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(rpcTimeout)); err != nil {
		return Message{}, err
	}
	if err := json.NewEncoder(conn).Encode(message); err != nil {
		return Message{}, err
	}
	var response Message
	err = json.NewDecoder(io.LimitReader(conn, 64*1024)).Decode(&response)
	return response, err
}

// Cả coordinator và participant đều dùng TCP server thật.
type socketServer struct {
	listener net.Listener
	handler  func(Message) Message
	mu       sync.Mutex
	active   map[net.Conn]bool
	stopping bool
	wg       sync.WaitGroup
	once     sync.Once
}

func newServer(listener net.Listener, handler func(Message) Message) *socketServer {
	s := &socketServer{listener: listener, handler: handler, active: make(map[net.Conn]bool)}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		s.accept()
	}()
	return s
}

func (s *socketServer) accept() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		if s.stopping {
			s.mu.Unlock()
			conn.Close()
			return
		}
		s.active[conn] = true
		s.wg.Add(1)
		s.mu.Unlock()
		go s.serve(conn)
	}
}

func (s *socketServer) serve(conn net.Conn) {
	defer s.wg.Done()
	defer func() {
		conn.Close()
		s.mu.Lock()
		delete(s.active, conn)
		s.mu.Unlock()
	}()
	if conn.SetDeadline(time.Now().Add(2*rpcTimeout)) != nil {
		return
	}
	var message Message
	if json.NewDecoder(io.LimitReader(conn, 64*1024)).Decode(&message) != nil {
		return
	}
	response := s.handler(message)
	_ = json.NewEncoder(conn).Encode(response)
}

func (s *socketServer) Close() {
	s.once.Do(func() {
		s.mu.Lock()
		s.stopping = true
		s.listener.Close()
		for conn := range s.active {
			conn.Close()
		}
		s.mu.Unlock()
	})
	s.wg.Wait()
}
