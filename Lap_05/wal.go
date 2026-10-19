package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
)

// Mỗi record là một dòng JSON. Participants chỉ xuất hiện ở START_2PC.
type Record struct {
	TxID         int    `json:"tx_id"`
	Event        string `json:"event"`
	Participants []int  `json:"participants,omitempty"`
}

// WAL chỉ cập nhật trạng thái bộ nhớ SAU KHI Append trả về thành công.
// Nếu Write/Sync lỗi, poison ngăn ghi tiếp vì kết quả lưu bền có thể mơ hồ.
type WAL struct {
	mu     sync.Mutex
	file   *os.File
	poison error
}

func openWAL(path string) (*WAL, []Record, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return nil, nil, err
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, nil, err
	}
	fail := func(err error) (*WAL, []Record, error) {
		file.Close()
		return nil, nil, err
	}
	data, err := io.ReadAll(file)
	if err != nil {
		return fail(err)
	}
	// Crash có thể để lại một dòng chưa ghi xong. Chỉ cắt phần cuối thiếu LF;
	// một dòng hoàn chỉnh nhưng JSON hỏng phải báo lỗi, không được bỏ qua.
	lastComplete := bytes.LastIndexByte(data, '\n') + 1
	if lastComplete != len(data) {
		if err := file.Truncate(int64(lastComplete)); err != nil {
			return fail(err)
		}
		if err := file.Sync(); err != nil {
			return fail(err)
		}
		data = data[:lastComplete]
	}
	var records []Record
	lines := bytes.Split(data, []byte{'\n'})
	for index, line := range lines[:len(lines)-1] {
		var record Record
		if err := json.Unmarshal(line, &record); err != nil {
			return fail(fmt.Errorf("WAL line %d: %w", index+1, err))
		}
		if record.TxID <= 0 || record.Event == "" {
			return fail(fmt.Errorf("invalid WAL record on line %d", index+1))
		}
		records = append(records, record)
	}
	if _, err := file.Seek(0, io.SeekEnd); err != nil {
		return fail(err)
	}
	return &WAL{file: file}, records, nil
}

func (w *WAL) Append(record Record) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.poison != nil {
		return w.poison
	}
	data, err := json.Marshal(record)
	if err != nil {
		return err
	}
	data = append(data, '\n')
	written, err := w.file.Write(data)
	if err == nil && written != len(data) {
		err = io.ErrShortWrite
	}
	if err == nil {
		err = w.file.Sync() // WAL ổn định TRƯỚC khi gửi vote/decision/ACK.
	}
	if err != nil {
		w.poison = fmt.Errorf("WAL write/sync failed; stop and recover before continuing: %w", err)
		return w.poison
	}
	return nil
}

func (w *WAL) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.file.Close()
}
