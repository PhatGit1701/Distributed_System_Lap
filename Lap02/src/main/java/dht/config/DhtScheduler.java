package dht.config;

import dht.node.DhtNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler Spring Boot cho các tác vụ nền của Chord DHT.
 *
 * <p>Thay thế {@code ScheduledExecutorService} thủ công bằng {@code @Scheduled}
 * của Spring Boot – dễ cấu hình hơn và tích hợp tốt với Spring context.
 *
 * <p><strong>Các tác vụ:</strong>
 * <ul>
 *   <li>{@code stabilize} – Sửa successor pointer và notify successor (mỗi 2 giây).</li>
 *   <li>{@code fixNextFinger} – Sửa một entry trong finger table (mỗi 1 giây).</li>
 *   <li>{@code checkPredecessor} – Kiểm tra predecessor còn sống không (mỗi 3 giây).</li>
 *   <li>{@code reregisterOwnedKeys} – Re-register keys định kỳ (mỗi 30 giây).</li>
 * </ul>
 */
@Component
public class DhtScheduler {

    private final DhtNode node;

    public DhtScheduler(DhtNode node) {
        this.node = node;
    }

    /**
     * Chord stabilize: chạy mỗi 2 giây sau 1 giây delay ban đầu.
     *
     * <p>Sửa successor pointer và cập nhật successor list.
     * fixedDelay đảm bảo một lần chạy hoàn thành trước khi lần tiếp theo bắt đầu.
     */
    @Scheduled(initialDelay = 1_000, fixedDelay = 2_000)
    public void stabilize() {
        if (node.isRunning()) {
            node.stabilize();
        }
    }

    /**
     * Fix one finger entry per invocation: chạy mỗi 1 giây.
     *
     * <p>Mỗi lần chỉ fix một finger (round-robin) để tránh tạo quá nhiều TCP connections.
     * Với M=8: sau 8 giây, toàn bộ finger table được cập nhật một lần.
     */
    @Scheduled(initialDelay = 2_000, fixedDelay = 1_000)
    public void fixNextFinger() {
        if (node.isRunning()) {
            node.fixNextFinger();
        }
    }

    /**
     * Check predecessor: chạy mỗi 3 giây.
     *
     * <p>Gửi PING tới predecessor; nếu không phản hồi → đặt predecessor=null
     * để ring có thể tự sửa qua stabilize.
     */
    @Scheduled(initialDelay = 3_000, fixedDelay = 3_000)
    public void checkPredecessor() {
        if (node.isRunning()) {
            node.checkPredecessor();
        }
    }

    /**
     * Re-register owned keys: chạy mỗi 30 giây.
     *
     * <p>Đảm bảo các keys mà node này sở hữu luôn được đăng ký đúng chỗ,
     * ngay cả khi ring thay đổi cấu trúc do node join/leave.
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void reregisterOwnedKeys() {
        if (node.isRunning()) {
            node.reregisterOwnedKeys();
        }
    }
}
