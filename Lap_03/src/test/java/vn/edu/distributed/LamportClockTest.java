package vn.edu.distributed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho {@link LamportClock}.
 *
 * <p>Kiểm tra cả ba quy tắc của đồng hồ Lamport:
 * <ol>
 *   <li>Sự kiện cục bộ: L = L + 1</li>
 *   <li>Sự kiện gửi  : L = L + 1 (giống cục bộ)</li>
 *   <li>Sự kiện nhận : L = max(L_local, L_msg) + 1</li>
 * </ol>
 */
@DisplayName("LamportClock")
class LamportClockTest {

    private LamportClock clock;

    @BeforeEach
    void setUp() {
        clock = new LamportClock();
    }

    // -----------------------------------------------------------------------
    // Trạng thái ban đầu
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Giá trị khởi tạo là 0")
    void initialValueIsZero() {
        assertEquals(0, clock.get());
    }

    // -----------------------------------------------------------------------
    // Quy tắc sự kiện cục bộ / gửi: L = L + 1
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tick() tăng từ 0 lên 1")
    void tickFromZero() {
        assertEquals(1, clock.tick());
        assertEquals(1, clock.get());
    }

    @Test
    @DisplayName("Ba lần tick liên tiếp: 0 → 1 → 2 → 3")
    void threeTicks() {
        clock.tick(); // 1
        clock.tick(); // 2
        int result = clock.tick(); // 3
        assertEquals(3, result);
        assertEquals(3, clock.get());
    }

    // -----------------------------------------------------------------------
    // Quy tắc sự kiện nhận: L = max(L_local, L_msg) + 1
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("update() dùng max+1 khi local < msg")
    void updateWhenLocalIsSmaller() {
        // local = 1, msg = 5 → kết quả mong đợi = max(1,5)+1 = 6
        clock.tick();              // local = 1
        int result = clock.update(5);
        assertEquals(6, result);
        assertEquals(6, clock.get());
    }

    @Test
    @DisplayName("update() dùng max+1 khi local > msg")
    void updateWhenLocalIsLarger() {
        // local = 10, msg = 3 → kết quả mong đợi = max(10,3)+1 = 11
        for (int i = 0; i < 10; i++) clock.tick();  // local = 10
        int result = clock.update(3);
        assertEquals(11, result);
    }

    @Test
    @DisplayName("update() dùng max+1 khi local == msg")
    void updateWhenEqual() {
        // local = 5, msg = 5 → kết quả mong đợi = max(5,5)+1 = 6
        for (int i = 0; i < 5; i++) clock.tick();
        int result = clock.update(5);
        assertEquals(6, result);
    }

    @Test
    @DisplayName("update() từ 0 với msg=0 → kết quả là 1")
    void updateFromZeroWithMsgZero() {
        // max(0,0)+1 = 1
        assertEquals(1, clock.update(0));
    }

    // -----------------------------------------------------------------------
    // Kịch bản theo đặc tả (specification)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Kịch bản spec: P0 local(L=1), P0 send(L=2), P1 recv(L_local=0, L_msg=2 → L=3)")
    void specScenario() {
        // Đồng hồ của P0
        LamportClock p0 = new LamportClock();
        int l1 = p0.tick();  // sự kiện cục bộ → 1
        assertEquals(1, l1);
        int l2 = p0.tick();  // sự kiện gửi → 2
        assertEquals(2, l2);

        // Đồng hồ của P1 nhận message có lamport=2
        LamportClock p1 = new LamportClock();
        int l3 = p1.update(2);  // max(0,2)+1 = 3
        assertEquals(3, l3);

        // Sự kiện cục bộ của P2 (độc lập, bắt đầu từ 0)
        LamportClock p2 = new LamportClock();
        int l4 = p2.tick();  // sự kiện cục bộ → 1
        assertEquals(1, l4);
    }
}
