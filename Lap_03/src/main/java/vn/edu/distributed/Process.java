package vn.edu.distributed;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class Process {
    private final int id;
    private final LamportClock lamport = new LamportClock();
    private final VectorClock vector;
    private final EventRegistry registry;
    private final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();

    public Process(int id, int size, EventRegistry registry) {
        this.id = id;
        this.vector = new VectorClock(size, id);
        this.registry = registry;
    }

    // Khóa toàn bộ sự kiện để hai đồng hồ và log luôn nhất quán.
    public synchronized void localEvent() {
        registry.record(id, "LOCAL EVENT", lamport.tick(), vector.tick());
    }

    public synchronized void send(Process target) {
        int time = lamport.tick();
        int[] snapshot = vector.tick();
        Message message = new Message(id, time, snapshot);
        registry.record(id, "SEND MSG to P" + target.id, time, snapshot);
        target.inbox.add(message);
    }

    public void receive() throws InterruptedException {
        // Chờ ngoài synchronized để không khóa tiến trình khi inbox rỗng.
        Message message = inbox.poll(5, TimeUnit.SECONDS);
        if (message == null)
            throw new IllegalStateException("P" + id + " không nhận được bản tin sau 5 giây");
        synchronized (this) {
            int time = lamport.update(message.lamport());
            int[] snapshot = vector.merge(message.vector());
            registry.record(id, "RECV MSG fr P" + message.senderId(), time, snapshot);
        }
    }
}
