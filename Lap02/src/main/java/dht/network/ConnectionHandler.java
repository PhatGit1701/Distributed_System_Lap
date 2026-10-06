package dht.network;

import dht.node.DhtNode;
import dht.node.FingerTable;
import dht.node.NodeInfo;
import dht.registry.RegistryEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.Socket;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Xử lý một kết nối TCP đến {@link DhtNode}.
 *
 * <p>Chạy trên thread pool (implements {@link Runnable}).
 * Đọc một request, dispatch, ghi một response, đóng kết nối.
 *
 * <p>Xem {@link MessageType} để biết toàn bộ giao thức.
 */
public final class ConnectionHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ConnectionHandler.class.getName());

    private final Socket socket;
    private final DhtNode node;

    public ConnectionHandler(Socket socket, DhtNode node) {
        this.socket = socket;
        this.node = node;
    }

    @Override
    public void run() {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String line = in.readLine();
            if (line == null) return;

            Message request;
            try {
                request = Message.parse(line);
            } catch (IllegalArgumentException e) {
                out.println(error("INVALID_MESSAGE", e.getMessage()));
                return;
            }

            Message response = dispatch(request);
            out.println(response.toWire());

        } catch (IOException e) {
            LOG.log(Level.FINE, "[CONN] I/O lỗi từ " + socket.getRemoteSocketAddress(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Dispatch: phân loại và xử lý từng loại message
    // -----------------------------------------------------------------------

    private Message dispatch(Message req) {
        try {
            return switch (req.type()) {
                case PING               -> handlePing();
                case GET_INFO           -> handleGetInfo();
                case GET_SUCCESSOR      -> handleGetSuccessor(req);
                case GET_PREDECESSOR    -> handleGetPredecessor();
                case SET_PREDECESSOR    -> handleSetPredecessor(req);
                case SET_SUCCESSOR      -> handleSetSuccessor(req);
                case GET_CLOSEST_FINGER -> handleGetClosestFinger(req);
                case NOTIFY             -> handleNotify(req);
                case REGISTER           -> handleRegister(req);
                case LOOKUP             -> handleLookup(req);
                case SEND               -> handleSend(req);
                case MESSAGE            -> handleMessage(req);
                case GET_FINGER_TABLE   -> handleGetFingerTable();
                // Fault tolerance messages
                case REPLICATE          -> handleReplicate(req);
                case MIGRATE_KEYS       -> handleMigrateKeys(req);
                case GET_SUCCESSOR_LIST -> handleGetSuccessorList();
                default                 -> error("UNSUPPORTED_COMMAND", req.type().name());
            };
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[CONN] Lỗi xử lý " + req.type(), e);
            return error("INTERNAL_ERROR", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    /** PING → PONG: kiểm tra node còn sống. */
    private Message handlePing() {
        return Message.of(MessageType.PONG);
    }

    /** GET_INFO → INFO: trả về NodeInfo của node này. */
    private Message handleGetInfo() {
        return Message.of(MessageType.INFO, node.getSelf().toWireString());
    }

    /**
     * GET_SUCCESSOR id → SUCCESSOR nodeInfoWire.
     *
     * <p>Chord routing: tìm successor của id, có thể forward tới nodes khác.
     */
    private Message handleGetSuccessor(Message req) throws IOException {
        BigInteger id = new BigInteger(req.payload().trim());
        NodeInfo successor = node.findSuccessor(id);
        return Message.of(MessageType.SUCCESSOR, successor.toWireString());
    }

    /** GET_PREDECESSOR → PREDECESSOR nodeInfoWire|NONE. */
    private Message handleGetPredecessor() {
        NodeInfo pred = node.getPredecessor();
        return Message.of(MessageType.PREDECESSOR, pred == null ? "NONE" : pred.toWireString());
    }

    /** SET_PREDECESSOR nodeInfoWire → OK. */
    private Message handleSetPredecessor(Message req) {
        node.setPredecessor(NodeInfo.fromWireString(req.payload()));
        return Message.of(MessageType.OK);
    }

    /** SET_SUCCESSOR nodeInfoWire → OK. */
    private Message handleSetSuccessor(Message req) {
        node.setSuccessor(NodeInfo.fromWireString(req.payload()));
        return Message.of(MessageType.OK);
    }

    /** GET_CLOSEST_FINGER id → CLOSEST_FINGER nodeInfoWire. */
    private Message handleGetClosestFinger(Message req) {
        BigInteger id = new BigInteger(req.payload().trim());
        NodeInfo closest = node.getFingerTable().closestPrecedingFinger(id);
        return Message.of(MessageType.CLOSEST_FINGER, closest.toWireString());
    }

    /**
     * NOTIFY nodeInfoWire → OK.
     *
     * <p>Chord stabilize: node khác báo "Tôi nghĩ tôi là predecessor của bạn."
     */
    private Message handleNotify(Message req) {
        node.notify(NodeInfo.fromWireString(req.payload()));
        return Message.of(MessageType.OK);
    }

    /**
     * REGISTER name host port → OK.
     *
     * <p>Đăng ký dịch vụ, có thể forward tới responsible node qua Chord routing.
     */
    private Message handleRegister(Message req) throws IOException {
        String[] parts = req.payload().split(" ", 3);
        if (parts.length != 3) return error("INVALID_ARGS", "REGISTER expects: <name> <host> <port>");
        String serviceName = parts[0];
        String host = parts[1];
        int port;
        try {
            port = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return error("INVALID_PORT", parts[2]);
        }
        node.handleRegister(serviceName, host, port);
        return Message.of(MessageType.OK);
    }

    /**
     * LOOKUP name → LOOKUP_RESULT nodeInfoWire|NONE.
     *
     * <p>Tra cứu dịch vụ, có thể forward tới responsible node.
     * Nếu responsible node chết → tìm trong successorList (replica).
     */
    private Message handleLookup(Message req) throws IOException {
        String name = req.payload().trim();
        NodeInfo result = node.handleLookup(name);
        return Message.of(MessageType.LOOKUP_RESULT, result == null ? "NONE" : result.toWireString());
    }

    /**
     * SEND name\tmessage → OK|ERROR.
     *
     * <p>Gửi message đến dịch vụ đã đăng ký:
     * 1. Lookup tên → lấy địa chỉ endpoint.
     * 2. Deliver message trực tiếp đến endpoint.
     */
    private Message handleSend(Message req) throws IOException {
        String payload = req.payload();
        int tab = payload.indexOf('\t');
        if (tab < 0) return error("INVALID_ARGS", "SEND expects: <name>\\t<message>");

        String name = payload.substring(0, tab);
        String message = payload.substring(tab + 1);

        LOG.info(String.format("[SEND] Đang giải quyết '%s' để gửi message", name));
        NodeInfo target = node.handleLookup(name);
        if (target == null) return error("NAME_NOT_FOUND", "Không tìm thấy: " + name);

        LOG.info(String.format("[SEND] Gửi message tới '%s' tại %s:%d", name, target.host(), target.port()));
        try {
            String result = SocketClient.deliverMessage(target.host(), target.port(), message);
            return Message.of(MessageType.OK, "Đã gửi tới " + target.host() + ":" + target.port() + " -> " + result);
        } catch (IOException e) {
            return error("NODE_UNAVAILABLE", target.host() + ":" + target.port() + " - " + e.getMessage());
        }
    }

    /** MESSAGE text → OK: nhận raw message (endpoint node). */
    private Message handleMessage(Message req) {
        String text = req.payload();
        LOG.info(String.format("[MESSAGE] Node '%s' nhận được: %s", node.getSelf().name(), text));
        System.out.printf("[MESSAGE] Node '%s' nhận được: %s%n", node.getSelf().name(), text);
        return Message.of(MessageType.OK, "Message nhận bởi " + node.getSelf().name());
    }

    /** GET_FINGER_TABLE → FINGER_TABLE data: debug dump. */
    private Message handleGetFingerTable() {
        FingerTable ft = node.getFingerTable();
        return Message.of(MessageType.FINGER_TABLE, ft.toWireString());
    }

    // -----------------------------------------------------------------------
    // Fault tolerance handlers
    // -----------------------------------------------------------------------

    /**
     * REPLICATE name host port → OK.
     *
     * <p>Lưu một bản sao (replica) của registry entry mà KHÔNG routing thêm.
     * Được gọi bởi node chịu trách nhiệm sau khi nó đã lưu entry chính.
     */
    private Message handleReplicate(Message req) {
        String[] parts = req.payload().split(" ", 3);
        if (parts.length != 3) return error("INVALID_ARGS", "REPLICATE expects: <name> <host> <port>");
        try {
            node.storeReplica(parts[0], parts[1], Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return error("INVALID_PORT", parts[2]);
        }
        return Message.of(MessageType.OK);
    }

    /**
     * MIGRATE_KEYS joinerNodeId → OK\tentry1\tentry2\t...
     *
     * <p><strong>Key migration khi node mới join:</strong><br>
     * Node mới gửi MIGRATE_KEYS với nodeId của nó.<br>
     * Node nhận (successor cũ) trả về tất cả keys thuộc phạm vi của joiner,
     * rồi xóa chúng khỏi storage của mình.
     */
    private Message handleMigrateKeys(Message req) {
        try {
            BigInteger joinerNodeId = new BigInteger(req.payload().trim());
            List<RegistryEntry> entries = node.getKeysForMigration(joinerNodeId);

            // Serialize tất cả entries thành chuỗi tab-separated
            StringBuilder sb = new StringBuilder();
            for (RegistryEntry e : entries) {
                if (sb.length() > 0) sb.append("\t");
                sb.append(e.toWireString());
            }

            // Xóa các keys đã migrate khỏi storage của mình
            node.removeKeysAfterMigration(joinerNodeId);

            LOG.info(String.format("[MIGRATE] Đã migrate %d keys tới joiner (id=%s)",
                    entries.size(), joinerNodeId));
            return Message.of(MessageType.OK, sb.toString());
        } catch (Exception e) {
            return error("MIGRATE_ERROR", e.getMessage());
        }
    }

    /**
     * GET_SUCCESSOR_LIST → SUCCESSOR_LIST wire1\twire2\t...
     *
     * <p>Trả về danh sách successor của node này (dùng cho fault tolerance).
     */
    private Message handleGetSuccessorList() {
        List<NodeInfo> list = node.getSuccessorList();
        if (list.isEmpty()) return Message.of(MessageType.SUCCESSOR_LIST, "NONE");
        StringBuilder sb = new StringBuilder();
        for (NodeInfo n : list) {
            if (sb.length() > 0) sb.append("\t");
            sb.append(n.toWireString());
        }
        return Message.of(MessageType.SUCCESSOR_LIST, sb.toString());
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static Message error(String code, String detail) {
        return Message.of(MessageType.ERROR, code + " " + (detail == null ? "" : detail));
    }
}
