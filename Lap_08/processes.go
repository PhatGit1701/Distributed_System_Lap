package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

type child struct {
	cmd  *exec.Cmd
	peer Peer
	done chan struct{}
	err  error // Đọc sau khi done đóng.
}
type Cluster struct {
	children []*child
	Peers    []Peer
	once     sync.Once
}

func startProcesses(interval time.Duration, log logFunc) (*Cluster, error) {
	if interval <= 0 {
		return nil, fmt.Errorf("interval must be positive")
	}
	executable, err := os.Executable()
	if err != nil {
		return nil, err
	}
	c := &Cluster{}
	for id := 1; id <= 5; id++ {
		cmd := exec.Command(executable, "replica", "-id", strconv.Itoa(id), "-interval", interval.String())
		pipe, err := cmd.StdoutPipe()
		if err != nil {
			c.Close()
			return nil, err
		}
		cmd.Stderr = cmd.Stdout
		if err = cmd.Start(); err != nil {
			pipe.Close()
			c.Close()
			return nil, err
		}
		process := &child{cmd: cmd, done: make(chan struct{})}
		c.children = append(c.children, process)
		ready := make(chan string, 1)
		go func(ch *child) {
			scanner := bufio.NewScanner(pipe)
			for scanner.Scan() {
				line := scanner.Text()
				if strings.HasPrefix(line, "READY ") {
					select {
					case ready <- strings.TrimPrefix(line, "READY "):
					default:
					}
				} else {
					log("%s", line)
				}
			}
			scanErr := scanner.Err()
			ch.err = ch.cmd.Wait()
			if ch.err == nil {
				ch.err = scanErr
			}
			close(ch.done)
		}(process)
		select {
		case address := <-ready:
			process.peer = Peer{ID: id, Address: address}
			c.Peers = append(c.Peers, process.peer)
			log("[SYSTEM] Started S%d PID=%d TCP=%s", id, cmd.Process.Pid, address)
		case <-process.done:
			c.Close()
			return nil, fmt.Errorf("S%d exited before READY: %v", id, process.err)
		case <-time.After(6 * time.Second):
			c.Close()
			return nil, fmt.Errorf("S%d startup timeout", id)
		}
	}
	for _, peer := range c.Peers {
		if _, err := rpc(peer.Address, Request{Type: "CONFIG", Peers: c.Peers, Enabled: false}); err != nil {
			c.Close()
			return nil, err
		}
	}
	return c, nil
}
func (c *Cluster) enableGossip() error {
	for _, peer := range c.Peers {
		if _, err := rpc(peer.Address, Request{Type: "ENABLE_GOSSIP", Enabled: true}); err != nil {
			return err
		}
	}
	return nil
}
func (c *Cluster) sync(from, to int) error {
	_, err := rpc(c.Peers[from-1].Address, Request{Type: "SYNC", Target: to})
	return err
}
func (c *Cluster) waitEntry(key string, want Entry, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		all := true
		for _, peer := range c.Peers {
			reply, err := rpc(peer.Address, Request{Type: "GET", Key: key})
			if err != nil || reply.Entry != want {
				all = false
			}
		}
		if all {
			return nil
		}
		time.Sleep(50 * time.Millisecond)
	}
	return fmt.Errorf("replicas did not converge for %q", key)
}
func (c *Cluster) Close() {
	c.once.Do(func() {
		// Đợi mỗi gossip worker hoàn tất trước khi ngừng các peer.
		for _, ch := range c.children {
			if ch.peer.Address != "" {
				_, _ = rpc(ch.peer.Address, Request{Type: "ENABLE_GOSSIP", Enabled: false})
			}
		}
		// Yêu cầu tất cả dừng trước, rồi thu hồi từng process.
		for _, ch := range c.children {
			if ch.peer.Address != "" {
				_, _ = rpc(ch.peer.Address, Request{Type: "STOP"})
			}
		}
		for _, ch := range c.children {
			select {
			case <-ch.done:
			case <-time.After(3 * time.Second):
				_ = ch.cmd.Process.Kill()
				<-ch.done
			}
		}
	})
}
