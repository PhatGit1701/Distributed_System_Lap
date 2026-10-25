package main

import (
	"context"
	"fmt"
	"sync"
	"time"
)

type logFunc func(string, ...any)

// Mỗi client có Session riêng; mutex giữ thứ tự thao tác trong cùng session.
type Session struct {
	mu    sync.Mutex
	read  Vector
	write Vector
}

func (s *Session) Vectors() (Vector, Vector) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.read, s.write
}

type Proxy struct {
	addresses [3]string
	log       logFunc
}

func (p *Proxy) ensure(ctx context.Context, target int, required Vector) error {
	if target < 1 || target > 3 {
		return fmt.Errorf("target must be S1..S3")
	}
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		state, err := rpc(p.addresses[target-1], Request{Type: "STATE"})
		if err == nil && state.Clock.Covers(required) {
			return nil
		}
		if err == nil {
			p.log("[PROXY] Validation Failed! S%d clock %v does not dominate session requirement %v", target, state.Clock, required)
			var writes []Write
			seen := make(map[string]Write)
			for i, address := range p.addresses {
				if err := ctx.Err(); err != nil {
					return err
				}
				source, e := rpc(address, Request{Type: "EXPORT", Required: required})
				if e != nil {
					continue
				}
				for _, w := range source.Writes {
					if w.Seq > state.Clock[w.Origin] {
						if old, ok := seen[w.ID()]; ok {
							if old != w {
								return fmt.Errorf("inconsistent write-set %s", w.ID())
							}
							continue
						}
						seen[w.ID()] = w
						writes = append(writes, w)
						p.log("[PROXY] Fetch write-set %s from S%d -> S%d", w.ID(), i+1, target)
					}
				}
			}
			if len(writes) > 0 {
				updated, e := rpc(p.addresses[target-1], Request{Type: "APPLY", Writes: writes})
				if e == nil && updated.Clock.Covers(required) {
					p.log("[S%d] Write-sets applied; clock %v", target, updated.Clock)
					return nil
				}
			}
		} else {
			p.log("[PROXY] S%d unavailable: %v", target, err)
		}
		p.log("[PROXY] S%d cannot yet satisfy %v -> STALL, do not return stale data", target, required)
		timer := time.NewTimer(40 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}

func (p *Proxy) Read(ctx context.Context, s *Session, target int, key string) (Response, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	required := merge(s.read, s.write)
	if err := p.ensure(ctx, target, required); err != nil {
		return Response{}, err
	}
	reply, err := rpc(p.addresses[target-1], Request{Type: "READ", Key: key, Required: required})
	if err != nil {
		return reply, err
	}
	if !reply.Clock.Covers(required) {
		return Response{}, fmt.Errorf("read returned stale vector")
	}
	s.read = merge(s.read, reply.Clock)
	p.log("[CLIENT] Read S%d key=%q -> %q found=%v | V_read=%v", target, key, reply.Value, reply.Found, s.read)
	return reply, nil
}

func (p *Proxy) Write(ctx context.Context, s *Session, target int, key, value string) (Response, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	required := merge(s.read, s.write)
	if err := p.ensure(ctx, target, required); err != nil {
		return Response{}, err
	}
	p.log("[CLIENT] Write key=%q value=%q -> S%d", key, value, target)
	reply, err := rpc(p.addresses[target-1], Request{Type: "WRITE", Key: key, Value: value, Required: required})
	if err != nil {
		return reply, err
	}
	if !reply.Clock.Covers(required) {
		return Response{}, fmt.Errorf("write acknowledgement lost dependencies")
	}
	s.write = merge(s.write, reply.Clock)
	p.log("[S%d] Write ACK | V_write=%v", target, s.write)
	return reply, nil
}

type Cluster struct {
	replicas []*Replica
	Proxy    *Proxy
	cancel   context.CancelFunc
	wg       sync.WaitGroup
	once     sync.Once
}

func startCluster(delay time.Duration, log logFunc) (*Cluster, error) {
	if delay <= 0 {
		return nil, fmt.Errorf("replication delay must be positive")
	}
	c := &Cluster{Proxy: &Proxy{log: log}}
	for id := 1; id <= 3; id++ {
		r, err := startReplica(id)
		if err != nil {
			c.Close()
			return nil, err
		}
		c.replicas = append(c.replicas, r)
		c.Proxy.addresses[id-1] = r.Address()
		log("[SYSTEM] S%d TCP server %s", id, r.Address())
	}
	ctx, cancel := context.WithCancel(context.Background())
	c.cancel = cancel
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		ticker := time.NewTicker(delay)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				// Bản sao cuối mỗi chu kỳ vẫn đi qua socket, không dùng chung map/history.
				var all []Write
				max := Vector{}
				for _, addr := range c.Proxy.addresses {
					state, err := rpc(addr, Request{Type: "STATE"})
					if err != nil {
						continue
					}
					max = merge(max, state.Clock)
					batch, err := rpc(addr, Request{Type: "EXPORT", Required: state.Clock})
					if err == nil {
						all = append(all, batch.Writes...)
					}
				}
				for i, addr := range c.Proxy.addresses {
					if reply, err := rpc(addr, Request{Type: "APPLY", Writes: all}); err == nil {
						log("[ASYNC] S%d clock %v (observed union %v)", i+1, reply.Clock, max)
					}
				}
			}
		}
	}()
	return c, nil
}

func (c *Cluster) Close() {
	c.once.Do(func() {
		if c.cancel != nil {
			c.cancel()
		}
		c.wg.Wait()
		for _, r := range c.replicas {
			r.server.Close()
		}
	})
}
