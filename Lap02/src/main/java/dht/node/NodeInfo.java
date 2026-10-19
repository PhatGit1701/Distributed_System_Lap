package dht.node;

import dht.routing.HashUtil;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Đối tượng bất biến (immutable) chứa danh tính logic và địa chỉ vật lý của một node DHT.
 *
 * <p><strong>Thiết kế quan trọng:</strong><br>
 * - {@code nodeId} = SHA-1(name) mod 2^M → KHÔNG bao giờ thay đổi, dù node đổi IP/port.<br>
 * - {@code host}, {@code port} = địa chỉ vật lý hiện tại → có thể thay đổi.<br>
 * - Điều này cho phép hệ thống chịu lỗi khi node đổi địa chỉ: tên logic không đổi,
 *   chỉ cần re-register để cập nhật địa chỉ mới.
 */
public record NodeInfo(
        String name,
        BigInteger nodeId,
        String host,
        int port
) {
    /**
     * Tạo {@code NodeInfo} và tự động tính {@code nodeId = SHA-1(name) mod 2^M}.
     *
     * @param name tên logic (ví dụ "nodeA")
     * @param host địa chỉ IP/hostname hiện tại
     * @param port cổng TCP hiện tại
     */
    public static NodeInfo of(String name, String host, int port) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(host, "host must not be null");
        return new NodeInfo(name, HashUtil.hash(name), host, port);
    }

    /**
     * Tạo bản sao với địa chỉ vật lý mới (nodeId giữ nguyên).
     *
     * <p>Dùng khi node đổi IP/port mà không muốn thay đổi vị trí trên ring.
     */
    public NodeInfo withAddress(String newHost, int newPort) {
        return new NodeInfo(name, nodeId, newHost, newPort);
    }

    /**
     * Chuyển thành chuỗi wire: {@code name|nodeId|host|port}.
     * Ký tự {@code |} là delimiter; giá trị không được chứa {@code |}.
     */
    public String toWireString() {
        return name + "|" + nodeId.toString() + "|" + host + "|" + port;
    }

    /**
     * Parse từ định dạng {@link #toWireString()}.
     */
    public static NodeInfo fromWireString(String s) {
        String[] parts = s.split("\\|", 4);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid NodeInfo wire format: " + s);
        }
        return new NodeInfo(parts[0], new BigInteger(parts[1]), parts[2], Integer.parseInt(parts[3]));
    }

    @Override
    public String toString() {
        // Với M=8, nodeId chỉ có tối đa 3 chữ số → hiện toàn bộ cho dễ đọc
        return String.format("NodeInfo{name='%s', id=%s, addr='%s:%d'}",
                name, nodeId.toString(), host, port);
    }
}
