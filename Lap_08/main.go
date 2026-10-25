package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"sync"
	"time"
)

type trace struct {
	mu  sync.Mutex
	out io.Writer
	err error
}

func (t *trace) log(f string, a ...any) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.err == nil {
		_, t.err = fmt.Fprintf(t.out, f+"\n", a...)
	}
}
func demo(out io.Writer, nr, nw int, interval time.Duration) error {
	if err := validateQuorum(nr, nw); err != nil {
		return err
	}
	logger := &trace{out: out}
	c, err := startProcesses(interval, logger.log)
	if err != nil {
		return err
	}
	defer c.Close()
	q, err := newQuorum(c.Peers, nr, nw, logger.log)
	if err != nil {
		return err
	}
	logger.log("[SYSTEM] N=5 NR=%d NW=%d. Background gossip paused for controlled divergence.", nr, nw)
	bob, err := q.Write("user1", "Bob", false)
	if err != nil {
		return err
	}
	for _, peer := range c.Peers {
		reply, e := rpc(peer.Address, Request{Type: "GET", Key: "user1"})
		if e != nil {
			return e
		}
		logger.log("[SYSTEM] S%d ver=%d (quorum write may leave other replicas stale)", peer.ID, reply.Entry.Version)
	}
	if err = c.sync(3, 4); err != nil {
		return err
	}
	if err = c.sync(4, 5); err != nil {
		return err
	}
	reply, err := rpc(c.Peers[3].Address, Request{Type: "GET", Key: "user1"})
	if err != nil || reply.Entry != bob {
		return fmt.Errorf("push-pull did not transfer Bob to S4")
	}
	logger.log("[VERIFY] Push-Pull synchronized Bob to S4 and S5.")
	tombstone, err := q.Write("user1", "", true)
	if err != nil {
		return err
	}
	logger.log("[CLIENT] Delete user1 -> Tombstone ver=%d", tombstone.Version)
	// S5 có bản cũ nếu NW<5. Nó đẩy bản cũ vào S1; tombstone mới phải thắng.
	if err = c.sync(5, 1); err != nil {
		return err
	}
	reply, err = rpc(c.Peers[0].Address, Request{Type: "GET", Key: "user1"})
	if err != nil || reply.Entry != tombstone {
		return fmt.Errorf("old value resurrected after delete")
	}
	logger.log("[VERIFY] Old Bob cannot overwrite newer tombstone.")
	logger.log("[SYSTEM] Enable periodic random-peer gossip every %s.", interval)
	if err = c.enableGossip(); err != nil {
		return err
	}
	if err = c.waitEntry("user1", tombstone, 8*interval+3*time.Second); err != nil {
		return err
	}
	latest, err := q.Read("user1")
	if err != nil || !latest.Tombstone {
		return fmt.Errorf("quorum read did not return deletion")
	}
	logger.log("[SYSTEM] All 5 replica processes converged via tombstone gossip.")
	c.Close()
	for _, ch := range c.children {
		if ch.err != nil {
			return fmt.Errorf("replica exit error: %v", ch.err)
		}
	}
	logger.log("[VERIFY] PASS: Gifford quorum, push-pull and no resurrection.")
	return logger.err
}
func main() {
	if len(os.Args) > 1 && os.Args[1] == "replica" {
		if err := runReplica(os.Args[2:], os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	nr := flag.Int("nr", 3, "read quorum")
	nw := flag.Int("nw", 3, "write quorum")
	interval := flag.Duration("interval", 2*time.Second, "gossip interval")
	path := flag.String("log", "trace.log", "console trace")
	flag.Parse()
	file, err := os.Create(*path)
	if err == nil {
		err = demo(io.MultiWriter(os.Stdout, file), *nr, *nw, *interval)
		closeErr := file.Close()
		if err == nil {
			err = closeErr
		}
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
