package dht.network;

import dht.node.NodeInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * TCP client đồng bộ: gửi một {@link Message} tới node từ xa và nhận một phản hồi.
 *
 * <p>Mỗi phương thức mở một socket mới, gửi request, đọc response, đóng socket.
 * Đơn giản nhưng đủ dùng cho bài lab (không cần persistent connection).
 *
 * <p>Tất cả các phương thức đặt timeout để tránh block vô hạn khi node chết.
 */
public final class SocketClient {

    /** Timeout kết nối TCP (ms). */
    public static final int CONNECT_TIMEOUT_MS = 3_000;

    /** Timeout đọc response (ms). */
    public static final int READ_TIMEOUT_MS = 5_000;

    private SocketClient() {}

    // -----------------------------------------------------------------------
    // Primitive: gửi 1 message, nhận 1 response
    // -----------------------------------------------------------------------

    /**
     * Kết nối TCP tới host:port, gửi {@code msg}, đọc một dòng response.
     *
     * @throws IOException nếu kết nối hoặc I/O thất bại (node chết / timeout)
     */
    public static Message sendAndReceive(String host, int port, Message msg) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(msg.toWire());
            String responseLine = in.readLine();
            if (responseLine == null) {
                throw new IOException("Connection closed by remote without response");
            }
            return Message.parse(responseLine);
        }
    }

    // -----------------------------------------------------------------------
    // Chord liveness
    // -----------------------------------------------------------------------

    /**
     * Gửi PING, kiểm tra node có còn sống không.
     *
     * @return true nếu nhận được PONG, false nếu node không phản hồi
     */
    public static boolean ping(String host, int port) {
        try {
            Message resp = sendAndReceive(host, port, Message.of(MessageType.PING));
            return resp.type() == MessageType.PONG;
        } catch (IOException e) {
            return false;
        }
    }

    /** Lấy NodeInfo của node tại host:port. */
    public static NodeInfo getInfo(String host, int port) throws IOException {
        Message resp = sendAndReceive(host, port, Message.of(MessageType.GET_INFO));
        if (resp.type() == MessageType.INFO) return NodeInfo.fromWireString(resp.payload());
        throw new IOException("Unexpected response to GET_INFO: " + resp);
    }

    // -----------------------------------------------------------------------
    // Chord routing
    // -----------------------------------------------------------------------

    /**
     * Hỏi node tại host:port: "successor của ID này là ai?"
     *
     * <p><strong>Chord lookup:</strong> Đây là lệnh cốt lõi để định tuyến.
     * Node nhận sẽ tiếp tục forward nếu nó không phải là responsible node.
     */
    public static NodeInfo findSuccessor(String host, int port, BigInteger id) throws IOException {
        Message req = Message.of(MessageType.GET_SUCCESSOR, id.toString());
        Message resp = sendAndReceive(host, port, req);
        if (resp.type() == MessageType.SUCCESSOR) return NodeInfo.fromWireString(resp.payload());
        if (resp.type() == MessageType.ERROR) throw new IOException("Remote error: " + resp.payload());
        throw new IOException("Unexpected response to GET_SUCCESSOR: " + resp);
    }

    /** Lấy predecessor của node tại host:port. Trả về null nếu không có. */
    public static NodeInfo getPredecessor(String host, int port) throws IOException {
        Message resp = sendAndReceive(host, port, Message.of(MessageType.GET_PREDECESSOR));
        if (resp.type() == MessageType.PREDECESSOR) {
            return "NONE".equals(resp.payload()) ? null : NodeInfo.fromWireString(resp.payload());
        }
        throw new IOException("Unexpected response to GET_PREDECESSOR: " + resp);
    }

    /** Yêu cầu node tại host:port cập nhật predecessor của nó. */
    public static void setPredecessor(String host, int port, NodeInfo pred) throws IOException {
        sendAndReceive(host, port, Message.of(MessageType.SET_PREDECESSOR, pred.toWireString()));
    }

    /** Yêu cầu node tại host:port cập nhật successor của nó. */
    public static void setSuccessor(String host, int port, NodeInfo succ) throws IOException {
        sendAndReceive(host, port, Message.of(MessageType.SET_SUCCESSOR, succ.toWireString()));
    }

    /** Lấy closest preceding finger của node tại host:port cho ID đích. */
    public static NodeInfo getClosestFinger(String host, int port, BigInteger id) throws IOException {
        Message req = Message.of(MessageType.GET_CLOSEST_FINGER, id.toString());
        Message resp = sendAndReceive(host, port, req);
        if (resp.type() == MessageType.CLOSEST_FINGER) return NodeInfo.fromWireString(resp.payload());
        throw new IOException("Unexpected response to GET_CLOSEST_FINGER: " + resp);
    }

    /**
     * Gửi NOTIFY tới node tại host:port.
     *
     * <p><strong>Chord stabilize – bước notify:</strong><br>
     * Node A nói với node B: "Tôi nghĩ tôi là predecessor của bạn."<br>
     * Node B sẽ chấp nhận nếu A thực sự nằm giữa predecessor cũ và B trên ring.
     */
    public static void notify(String host, int port, NodeInfo potentialPred) throws IOException {
        sendAndReceive(host, port, Message.of(MessageType.NOTIFY, potentialPred.toWireString()));
    }

    // -----------------------------------------------------------------------
    // Fault tolerance: successor list
    // -----------------------------------------------------------------------

    /**
     * Lấy danh sách r successor của node tại host:port.
     *
     * <p>Dùng để xây dựng {@code successorList} phòng khi primary successor chết.
     *
     * @return danh sách NodeInfo (có thể rỗng nếu node không hỗ trợ hoặc chết)
     */
    public static List<NodeInfo> getSuccessorList(String host, int port) throws IOException {
        Message resp = sendAndReceive(host, port, Message.of(MessageType.GET_SUCCESSOR_LIST));
        if (resp.type() == MessageType.SUCCESSOR_LIST) {
            List<NodeInfo> list = new ArrayList<>();
            if (resp.payload() != null && !resp.payload().isBlank() && !"NONE".equals(resp.payload())) {
                for (String wire : resp.payload().split("\t")) {
                    if (!wire.isBlank()) list.add(NodeInfo.fromWireString(wire));
                }
            }
            return list;
        }
        throw new IOException("Unexpected response to GET_SUCCESSOR_LIST: " + resp);
    }

    // -----------------------------------------------------------------------
    // Registry / messaging
    // -----------------------------------------------------------------------

    /** Đăng ký một dịch vụ vào DHT. */
    public static void register(String host, int port,
                                 String serviceName, String svcHost, int svcPort) throws IOException {
        String payload = serviceName + " " + svcHost + " " + svcPort;
        Message resp = sendAndReceive(host, port, Message.of(MessageType.REGISTER, payload));
        if (resp.type() != MessageType.OK) throw new IOException("REGISTER failed: " + resp);
    }

    /** Tra cứu một dịch vụ trong DHT. Trả về null nếu không tìm thấy. */
    public static NodeInfo lookup(String host, int port, String name) throws IOException {
        Message resp = sendAndReceive(host, port, Message.of(MessageType.LOOKUP, name));
        if (resp.type() == MessageType.LOOKUP_RESULT) {
            return "NONE".equals(resp.payload()) ? null : NodeInfo.fromWireString(resp.payload());
        }
        if (resp.type() == MessageType.ERROR) return null;
        throw new IOException("Unexpected response to LOOKUP: " + resp);
    }

    /**
     * Gửi một bản sao registry entry tới node (REPLICATE).
     *
     * <p><strong>Replication:</strong> Sau khi lưu entry tại node chịu trách nhiệm,
     * entry này được replicate tới r successors để đảm bảo fault tolerance.
     */
    public static void replicate(String host, int port,
                                  String serviceName, String svcHost, int svcPort) throws IOException {
        String payload = serviceName + " " + svcHost + " " + svcPort;
        sendAndReceive(host, port, Message.of(MessageType.REPLICATE, payload));
    }

    /**
     * Yêu cầu node target trả về tất cả keys nó đang giữ (để migrate khi join).
     *
     * <p><strong>Key migration:</strong> Khi node mới join, nó cần lấy về các keys
     * mà trước đây successor đang giữ thay mình.
     *
     * @return payload raw của response (tab-separated RegistryEntry wire strings)
     */
    public static String migrateKeys(String host, int port, String joinerNodeId) throws IOException {
        Message resp = sendAndReceive(host, port,
                Message.of(MessageType.MIGRATE_KEYS, joinerNodeId));
        if (resp.type() == MessageType.OK) return resp.payload() != null ? resp.payload() : "";
        return "";
    }

    /** Gửi message đến dịch vụ đã đăng ký (qua node gateway). */
    public static String sendMessage(String host, int port, String name, String message) throws IOException {
        String payload = name + "\t" + message;
        Message resp = sendAndReceive(host, port, Message.of(MessageType.SEND, payload));
        return resp.toWire();
    }

    /** Deliver raw MESSAGE đến endpoint. */
    public static String deliverMessage(String host, int port, String text) throws IOException {
        Message resp = sendAndReceive(host, port, Message.of(MessageType.MESSAGE, text));
        return resp.toWire();
    }

    /** Lấy finger table của node (debug). */
    public static String getFingerTable(String host, int port) throws IOException {
        Message resp = sendAndReceive(host, port, Message.of(MessageType.GET_FINGER_TABLE));
        return resp.payload();
    }
}
