package main

import (
	"context"
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

func demo(out io.Writer, delay time.Duration) error {
	logger := &trace{out: out}
	c, err := startCluster(delay, logger.log)
	if err != nil {
		return err
	}
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	session := &Session{}
	logger.log("[SYSTEM] Async replication interval %s; session starts at [0 0 0].", delay)
	if _, err = c.Proxy.Write(ctx, session, 1, "profile", "Alice"); err != nil {
		return err
	}
	logger.log("[SYSTEM] Route the same client to stale S2: Read-Your-Writes check")
	first, err := c.Proxy.Read(ctx, session, 2, "profile")
	if err != nil {
		return err
	}
	if !first.Found || first.Value != "Alice" {
		return fmt.Errorf("read-your-writes failed")
	}
	logger.log("[VERIFY] Read-Your-Writes preserved!")
	// Một session khác ghi bản mới; client đầu tiên đọc được rồi đổi replica.
	writer := &Session{}
	if _, err = c.Proxy.Write(ctx, writer, 2, "profile", "Bob"); err != nil {
		return err
	}
	if _, err = c.Proxy.Read(ctx, session, 2, "profile"); err != nil {
		return err
	}
	logger.log("[SYSTEM] Route client to stale S3: Monotonic Reads check")
	last, err := c.Proxy.Read(ctx, session, 3, "profile")
	if err != nil {
		return err
	}
	if !last.Found || last.Value != "Bob" {
		return fmt.Errorf("monotonic reads failed")
	}
	read, write := session.Vectors()
	logger.log("[VERIFY] Monotonic Reads preserved! V_read=%v V_write=%v", read, write)
	c.Close()
	logger.log("[VERIFY] PASS: vector validation, missing write-set fetching and both session guarantees.")
	return logger.err
}

func main() {
	delay := flag.Duration("delay", 3*time.Second, "chu kỳ replication nền")
	path := flag.String("log", "trace.log", "console trace")
	flag.Parse()
	file, err := os.Create(*path)
	if err == nil {
		err = demo(io.MultiWriter(os.Stdout, file), *delay)
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
