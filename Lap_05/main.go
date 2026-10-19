package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

func main() {
	dataRoot := flag.String("data", "data", "thư mục chứa các lần chạy WAL riêng biệt")
	resume := flag.String("resume", "", "khôi phục một thư mục WAL có sẵn, ví dụ data/run-123")
	logPath := flag.String("log", "trace.log", "file console trace (ghi đè khi chạy)")
	flag.Parse()
	if err := run(*dataRoot, *resume, *logPath); err != nil {
		fmt.Fprintln(os.Stderr, "[ERROR]", err)
		os.Exit(1)
	}
}

func run(dataRoot, resume, logPath string) error {
	var directory string
	var err error
	if resume != "" {
		info, statErr := os.Stat(resume)
		if statErr != nil {
			return fmt.Errorf("resume directory: %w", statErr)
		}
		if !info.IsDir() {
			return fmt.Errorf("resume must be a WAL directory")
		}
		// Không âm thầm tạo WAL rỗng khi người dùng trỏ nhầm thư mục.
		for _, name := range []string{"coordinator.wal", "participant-1.wal", "participant-2.wal", "participant-3.wal"} {
			if _, err := os.Stat(filepath.Join(resume, name)); err != nil {
				return fmt.Errorf("missing recovery file %s: %w", name, err)
			}
		}
		directory = resume
	} else {
		if err := os.MkdirAll(dataRoot, 0755); err != nil {
			return err
		}
		directory, err = os.MkdirTemp(dataRoot, "run-")
		if err != nil {
			return err
		}
	}
	directory, err = filepath.Abs(directory)
	if err != nil {
		return err
	}
	file, err := os.Create(logPath)
	if err != nil {
		return err
	}
	out := io.MultiWriter(os.Stdout, file)
	if resume != "" {
		err = runRecovery(directory, out)
	} else {
		err = runDemo(directory, out)
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	return closeErr
}
