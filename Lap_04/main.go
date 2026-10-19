package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"time"
)

func main() {
	logPath := flag.String("log", "trace.log", "đường dẫn lưu console trace")
	crashAfter := flag.Duration("crash-after", time.Second, "thời gian chạy trước khi dừng P5")
	flag.Parse()
	if *crashAfter < 0 {
		fmt.Fprintln(os.Stderr, "crash-after must be non-negative")
		os.Exit(1)
	}
	file, err := os.Create(*logPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	err = simulate(ctx, io.MultiWriter(os.Stdout, file), *crashAfter)
	cancel()
	closeErr := file.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
