package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

const rpcTimeout = 400 * time.Millisecond

func rpc(address string, req Request) (Response, error) {
	conn, err := net.DialTimeout("tcp", address, rpcTimeout)
	if err != nil {
		return Response{}, err
	}
	defer conn.Close()
	if err = conn.SetDeadline(time.Now().Add(rpcTimeout)); err != nil {
		return Response{}, err
	}
	if err = json.NewEncoder(conn).Encode(req); err != nil {
		return Response{}, err
	}
	var reply Response
	if err = json.NewDecoder(io.LimitReader(conn, 8<<20)).Decode(&reply); err != nil {
		return reply, err
	}
	if reply.Error != "" {
		return reply, fmt.Errorf("%s", reply.Error)
	}
	return reply, nil
}

type tcpServer struct {
	listener net.Listener
	handler  func(Request) Response
	mu       sync.Mutex
	conns    map[net.Conn]bool
	closed   bool
	once     sync.Once
	wg       sync.WaitGroup
}

func listen(handler func(Request) Response) (*tcpServer, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	s := &tcpServer{listener: ln, handler: handler, conns: make(map[net.Conn]bool)}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			s.mu.Lock()
			if s.closed {
				s.mu.Unlock()
				conn.Close()
				return
			}
			s.conns[conn] = true
			s.wg.Add(1)
			s.mu.Unlock()
			go s.serve(conn)
		}
	}()
	return s, nil
}

func (s *tcpServer) serve(conn net.Conn) {
	defer s.wg.Done()
	defer func() { conn.Close(); s.mu.Lock(); delete(s.conns, conn); s.mu.Unlock() }()
	if conn.SetDeadline(time.Now().Add(2*rpcTimeout)) != nil {
		return
	}
	var req Request
	if json.NewDecoder(io.LimitReader(conn, 8<<20)).Decode(&req) != nil {
		return
	}
	_ = json.NewEncoder(conn).Encode(s.handler(req))
}

func (s *tcpServer) Close() {
	s.once.Do(func() {
		s.mu.Lock()
		s.closed = true
		s.listener.Close()
		for c := range s.conns {
			c.Close()
		}
		s.mu.Unlock()
	})
	s.wg.Wait()
}
