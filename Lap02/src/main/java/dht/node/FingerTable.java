package dht.node;

import dht.routing.HashUtil;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bảng finger (Finger Table) của một node Chord.
 *
 * <p><strong>Ý nghĩa:</strong><br>
 * Finger table có {@code M} entries. Entry thứ i (1-based) trỏ đến node
 * là successor của {@code (nodeId + 2^(i-1)) mod 2^M}.
 *
 * <p>Với M=8: có 8 fingers trỏ đến các khoảng cách 1, 2, 4, 8, 16, 32, 64, 128 trên ring.
 * Điều này đảm bảo tra cứu O(log N) hop vì mỗi hop ít nhất giảm một nửa khoảng cách còn lại.
 *
 * <p><strong>Thread-safe:</strong> sử dụng {@link AtomicReferenceArray}.
 */
public final class FingerTable {

    private final NodeInfo owner;
    private final AtomicReferenceArray<NodeInfo> fingers;

    /** Số lượng finger entries = M (kích thước ring tính bằng bit). */
    public static int SIZE = HashUtil.getM();

    public FingerTable(NodeInfo owner) {
        this.owner = owner;
        // Cập nhật SIZE theo M hiện tại (có thể đã thay đổi qua HashUtil.setM)
        SIZE = HashUtil.getM();
        this.fingers = new AtomicReferenceArray<>(SIZE);
        // Khởi tạo tất cả fingers trỏ về chính mình (single-node ring)
        for (int i = 0; i < SIZE; i++) {
            fingers.set(i, owner);
        }
    }

    /**
     * Lấy finger entry thứ i (1-based).
     *
     * @param i chỉ số finger trong [1..M]
     */
    public NodeInfo get(int i) {
        return fingers.get(i - 1); // lưu trữ nội bộ 0-based
    }

    /**
     * Đặt finger entry thứ i (1-based).
     *
     * @param i    chỉ số finger trong [1..M]
     * @param node node cần lưu vào slot này
     */
    public void set(int i, NodeInfo node) {
        fingers.set(i - 1, node);
    }

    /**
     * Tìm finger gần nhất đứng trước {@code id} trên ring.
     *
     * <p><strong>Thuật toán Chord – closest_preceding_finger:</strong><br>
     * Duyệt từ finger lớn nhất xuống, tìm finger f đầu tiên sao cho
     * {@code f.nodeId ∈ (owner.nodeId, id)} trên vòng ring.<br>
     * Finger này sẽ được dùng để forward request, giảm khoảng cách nhanh nhất.
     *
     * @param id ID đích cần tra cứu
     * @return finger gần nhất trước id, hoặc owner nếu không có finger nào phù hợp
     */
    public NodeInfo closestPrecedingFinger(BigInteger id) {
        BigInteger ownerId = owner.nodeId();
        // Duyệt từ finger lớn nhất (khoảng cách xa nhất) xuống dưới
        for (int i = SIZE; i >= 1; i--) {
            NodeInfo f = fingers.get(i - 1);
            if (f == null) continue;
            // Kiểm tra f.nodeId nằm trong khoảng mở (ownerId, id)
            if (HashUtil.inOpenInterval(f.nodeId(), ownerId, id)) {
                return f;
            }
        }
        // Không có finger nào phù hợp → trả về chính mình
        return owner;
    }

    /** @return successor (finger[1]) của node này */
    public NodeInfo getSuccessor() {
        return fingers.get(0);
    }

    /** Đặt successor (finger[1]) */
    public void setSuccessor(NodeInfo succ) {
        fingers.set(0, succ);
    }

    /**
     * In finger table dưới dạng dễ đọc, chỉ hiện các entry khác nhau.
     */
    public String prettyPrint() {
        StringBuilder sb = new StringBuilder();
        String lastNodeId = null;
        for (int i = 1; i <= SIZE; i++) {
            NodeInfo f = fingers.get(i - 1);
            String idStr = f == null ? "null" : f.nodeId().toString();
            if (!idStr.equals(lastNodeId)) {
                sb.append(String.format("  finger[%2d] -> %s (%s:%d) id=%s%n",
                        i,
                        f == null ? "null" : f.name(),
                        f == null ? "?" : f.host(),
                        f == null ? 0 : f.port(),
                        idStr));
                lastNodeId = idStr;
            }
        }
        return sb.toString();
    }

    /** Serialize finger table thành chuỗi wire (tab-separated NodeInfo). */
    public String toWireString() {
        StringBuilder sb = new StringBuilder();
        String lastId = null;
        for (int i = 1; i <= SIZE; i++) {
            NodeInfo f = get(i);
            if (f == null) continue;
            String idStr = f.nodeId().toString();
            if (!idStr.equals(lastId)) {
                if (sb.length() > 0) sb.append("\t");
                sb.append(f.toWireString());
                lastId = idStr;
            }
        }
        return sb.toString();
    }
}
