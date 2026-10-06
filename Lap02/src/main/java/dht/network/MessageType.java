package dht.network;

/**
 * Tất cả các loại message trong giao thức DHT (TCP socket).
 *
 * <p>Format wire: {@code TYPE\tPAYLOAD\n} hoặc {@code TYPE\n} khi không có payload.
 *
 * <pre>
 * PING / PONG              – kiểm tra node còn sống không (heartbeat)
 * GET_INFO / INFO          – lấy NodeInfo của node đích
 * GET_SUCCESSOR / SUCCESSOR       – Chord: tìm successor của một ID
 * GET_PREDECESSOR / PREDECESSOR   – lấy predecessor của node đích
 * SET_PREDECESSOR / SET_SUCCESSOR – cập nhật predecessor/successor
 * GET_CLOSEST_FINGER / CLOSEST_FINGER – tìm finger gần nhất
 * NOTIFY                   – Chord stabilize: thông báo "tôi nghĩ tôi là predecessor của bạn"
 * REGISTER                 – đăng ký một dịch vụ vào DHT
 * LOOKUP / LOOKUP_RESULT   – tra cứu dịch vụ
 * SEND / MESSAGE           – gửi message đến dịch vụ đã đăng ký
 * REPLICATE                – sao chép registry entry đến successor (fault tolerance)
 * MIGRATE_KEYS             – node mới join yêu cầu chuyển keys nó chịu trách nhiệm
 * GET_SUCCESSOR_LIST / SUCCESSOR_LIST – lấy danh sách r successors (fault tolerance)
 * OK / ERROR               – phản hồi thành công/lỗi
 * GET_FINGER_TABLE / FINGER_TABLE  – debug: dump finger table
 * </pre>
 */
public enum MessageType {
    // Liveness
    PING, PONG,
    // Node info
    GET_INFO, INFO,
    // Chord routing
    GET_SUCCESSOR, SUCCESSOR,
    GET_PREDECESSOR, PREDECESSOR,
    SET_PREDECESSOR, SET_SUCCESSOR,
    GET_CLOSEST_FINGER, CLOSEST_FINGER,
    NOTIFY,
    // Registry
    REGISTER, LOOKUP, LOOKUP_RESULT,
    // Messaging
    SEND, MESSAGE,
    // Fault tolerance
    REPLICATE,          // sao chép 1 entry tới successor
    MIGRATE_KEYS,       // chuyển keys khi node mới join
    GET_SUCCESSOR_LIST, // lấy danh sách r successors
    SUCCESSOR_LIST,     // response chứa danh sách successor
    // Generic
    OK, ERROR,
    // Debug
    GET_FINGER_TABLE, FINGER_TABLE
}
