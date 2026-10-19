package vn.edu.distributed;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public final class Main {
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        EventRegistry registry = new EventRegistry(3);
        Process p0 = new Process(0, 3, registry);
        Process p1 = new Process(1, 3, registry);
        Process p2 = new Process(2, 3, registry);

        // FutureTask chứa việc cần chạy và cho phép chờ kết quả bằng get().
        FutureTask<Void> task0 = new FutureTask<>(() -> {
            p0.localEvent();
            p0.send(p1);
            return null;
        });
        FutureTask<Void> task1 = new FutureTask<>(() -> {
            p1.receive(); // Inbox tự chờ bản tin từ P0.
            return null;
        });
        FutureTask<Void> task2 = new FutureTask<>(() -> {
            task1.get(); // Giữ thứ tự log như ví dụ đề bài.
            p2.localEvent();
            return null;
        });

        // Mỗi tiến trình có một thread riêng.
        new Thread(task0, "P0").start();
        new Thread(task1, "P1").start();
        new Thread(task2, "P2").start();

        // Chờ kết thúc; lỗi của thread được báo về main.
        task0.get();
        task1.get();
        task2.get();
    }
}
