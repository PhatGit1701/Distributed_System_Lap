package dht.node;

import dht.network.Message;
import dht.network.MessageType;
import dht.network.SocketClient;
import dht.registry.Registry;
import dht.registry.RegistryEntry;
import dht.routing.HashUtil;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Node tham gia vào vòng Chord DHT.
 *
 * <p><strong>Trách nhiệm chính:</strong>
 * <ol>
 *   <li>Duy trì {@link FingerTable} cho routing O(log N) hop.</li>
 *   <li>Theo dõi predecessor và successor list (r nodes) cho fault tolerance.</li>
 *   <li>Chấp nhận kết nối TCP và xử lý qua {@link dht.network.ConnectionHandler}.</li>
 *   <li>Lưu trữ registry cục bộ cho keys thuộc phạm vi trách nhiệm của node này.</li>
 *   <li>Replicate mỗi entry tới r successor để đảm bảo chịu lỗi.</li>
 * </ol>
 *
 * <p><strong>Fault Tolerance:</strong>
 * <ul>
 *   <li>Successor list (r=3): khi successor chính chết → dùng node tiếp theo trong list.</li>
 *   <li>PING heartbeat: checkPredecessor và stabilize kiểm tra node còn sống trước khi dùng.</li>
 *   <li>Key replication: mỗi entry được ghi tới r successors → chịu được r-1 node chết.</li>
 *   <li>Periodic re-register: các key được re-register định kỳ phòng khi ring thay đổi cấu trúc.</li>
 * </ul>
 *
 * <p><strong>Lưu ý Spring Boot:</strong>
 * Class này KHÔNG phải @Component để dễ dùng trong test (không cần Spring context).
 * Việc wiring vào Spring được thực hiện trong {@code DhtNodeConfig}.
 */
public final class DhtNode {

    private static final Logger LOG = Logger.getLogger(DhtNode.class.getName());

    // -----------------------------------------------------------------------
    // Cấu hình fault tolerance
    // -----------------------------------------------------------------------

    /** Số lượng successor tối đa trong successor list. */
    private final int successorListSize;

    // -----------------------------------------------------------------------
    // Trạng thái Chord ring
    // -----------------------------------------------------------------------

    /** Danh tính và địa chỉ vật lý của node này. */
    private volatile NodeInfo self;

    /**
     * Predecessor trên vòng ring (null nếu chưa biết).
     *
     * <p>Predecessor là node đứng ngay trước node này trên vòng Chord (clockwise).
     * Node này chịu trách nhiệm về các key trong khoảng (predecessor.nodeId, self.nodeId].
     */
    private final AtomicReference<NodeInfo> predecessor = new AtomicReference<>(null);

    /**
     * Bảng finger – finger[1] cũng chính là successor chính.
     *
     * <p>Mỗi entry finger[i] trỏ đến successor của (nodeId + 2^(i-1)) mod 2^M.
     */
    private final FingerTable fingerTable;

    /**
     * Danh sách r successor (successor list) – fault tolerance.
     *
     * <p>Khi successor chính không phản hồi, node dùng successorList[0], [1], ...
     * Danh sách được cập nhật mỗi lần stabilize chạy.
     */
    private final CopyOnWriteArrayList<NodeInfo> successorList = new CopyOnWriteArrayList<>();

    /** Registry cục bộ: các key mà node này chịu trách nhiệm lưu. */
    private final Registry registry = new Registry();

    /**
     * Cache keys mà node này đã đăng ký (để re-register định kỳ).
     *
     * <p>Key = serviceName, Value = NodeInfo của service endpoint.
     * Dùng cho tính năng re-register khi ring thay đổi cấu trúc.
     */
    private final Map<String, NodeInfo> ownedKeys = new java.util.concurrent.ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Concurrency
    // -----------------------------------------------------------------------

    private ServerSocket serverSocket;
    private final ExecutorService connectionPool = Executors.newCachedThreadPool();
    private Thread acceptorThread;
    private volatile boolean running = false;

    // -----------------------------------------------------------------------
    // Khởi tạo
    // -----------------------------------------------------------------------

    /**
     * Tạo node DHT mới.
     *
     * @param name             tên logic (ví dụ "nodeA") – dùng để tính nodeId
     * @param host             địa chỉ IP lắng nghe
     * @param port             cổng TCP lắng nghe
     * @param successorListSize số successor tối đa trong successor list (r)
     */
    public DhtNode(String name, String host, int port, int successorListSize) {
        this.self = NodeInfo.of(name, host, port);
        this.fingerTable = new FingerTable(self);
        this.successorListSize = successorListSize;
        LOG.info(String.format("[NODE] Tạo node %s tại %s:%d, nodeId=%s",
                name, host, port, self.nodeId()));
    }

    /** Constructor với successorListSize mặc định = 3. */
    public DhtNode(String name, String host, int port) {
        this(name, host, port, 3);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public NodeInfo getSelf() { return self; }
    public NodeInfo getPredecessor() { return predecessor.get(); }
    public void setPredecessor(NodeInfo p) { predecessor.set(p); }
    public NodeInfo getSuccessor() { return fingerTable.getSuccessor(); }
    public void setSuccessor(NodeInfo s) { fingerTable.setSuccessor(s); }
    public FingerTable getFingerTable() { return fingerTable; }
    public Registry getRegistry() { return registry; }
    public boolean isRunning() { return running; }

    /** @return danh sách successor list (bản sao snapshot). */
    public List<NodeInfo> getSuccessorList() {
        return List.copyOf(successorList);
    }

    // -----------------------------------------------------------------------
    // Vòng đời ring
    // -----------------------------------------------------------------------

    /**
     * Khởi động node như một ring đơn lẻ (bootstrap node).
     *
     * <p><strong>Single-node ring:</strong> Successor = chính mình, predecessor = null.
     * Đây là trạng thái ban đầu hợp lệ cho Chord.
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(self.port());
        running = true;

        // Ring đơn: successor là chính mình
        fingerTable.setSuccessor(self);
        predecessor.set(null);
        successorList.clear();

        startAcceptor();
        LOG.info(String.format("[NODE] Đã khởi động %s tại %s:%d (id=%s)",
                self.name(), self.host(), self.port(), self.nodeId()));
    }

    /**
     * Join vào một ring đang tồn tại qua bootstrap node.
     *
     * <p><strong>Quy trình join Chord:</strong>
     * <ol>
     *   <li>Hỏi bootstrap: "successor của nodeId của tôi là ai?" → đặt làm successor.</li>
     *   <li>Khởi tạo tất cả fingers = successor (tạm thời).</li>
     *   <li>Gọi {@code migrateKeys} để lấy về các key mà node này bây giờ chịu trách nhiệm.</li>
     *   <li>Bắt đầu stabilize/fixFingers để ring hội tụ dần.</li>
     * </ol>
     *
     * @param bootstrapHost host của node đã có trong ring
     * @param bootstrapPort port của node đã có trong ring
     */
    public void join(String bootstrapHost, int bootstrapPort) throws IOException {
        serverSocket = new ServerSocket(self.port());
        running = true;
        predecessor.set(null);
        successorList.clear();

        LOG.info(String.format("[DHT] %s đang join ring qua %s:%d", self.name(), bootstrapHost, bootstrapPort));

        // Bước 1: Tìm successor của nodeId của mình
        NodeInfo succ = SocketClient.findSuccessor(bootstrapHost, bootstrapPort, self.nodeId());
        fingerTable.setSuccessor(succ);

        // Bước 2: Khởi tạo tất cả fingers về successor (sẽ được sửa dần bởi fixFingers)
        for (int i = 2; i <= FingerTable.SIZE; i++) {
            fingerTable.set(i, succ);
        }

        startAcceptor();

        // Bước 3: Migrate keys từ successor (các key mà mình bây giờ chịu trách nhiệm)
        try {
            migrateKeysFromSuccessor(succ);
        } catch (IOException e) {
            LOG.warning("[DHT] Không thể migrate keys: " + e.getMessage());
        }

        LOG.info(String.format("[DHT] %s đã join ring, successor=%s (id=%s)",
                self.name(), succ.name(), succ.nodeId()));
    }

    /**
     * Dừng node, giải phóng tài nguyên.
     */
    public void stop() {
        running = false;
        connectionPool.shutdownNow();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        if (acceptorThread != null) acceptorThread.interrupt();
        LOG.info("[NODE] Đã dừng " + self.name());
    }

    // -----------------------------------------------------------------------
    // Chord routing
    // -----------------------------------------------------------------------

    /**
     * Tìm successor của {@code id} theo thuật toán Chord.
     *
     * <p><strong>Thuật toán Chord – find_successor:</strong>
     * <ol>
     *   <li>Base case: nếu {@code id ∈ (self.nodeId, successor.nodeId]} → trả về successor.</li>
     *   <li>Recursive case: tìm {@code closestPrecedingFinger(id)} và hỏi node đó tiếp tục lookup.</li>
     * </ol>
     *
     * <p>Mỗi hop ít nhất giảm một nửa khoảng cách đến đích → O(log N) hop.
     *
     * @param id  identifier cần tìm successor
     * @return NodeInfo của successor chịu trách nhiệm về id
     */
    public NodeInfo findSuccessor(BigInteger id) throws IOException {
        NodeInfo succ = getAliveSuccessor(); // lấy successor đang sống

        // Base case: id nằm trong khoảng (self, successor] → successor là đáp án
        if (HashUtil.inHalfOpenInterval(id, self.nodeId(), succ.nodeId())) {
            return succ;
        }

        // Recursive case: forward tới finger gần nhất trước id
        NodeInfo closest = fingerTable.closestPrecedingFinger(id);

        if (closest.nodeId().equals(self.nodeId())) {
            // Không có finger nào tốt hơn → trả về successor của mình
            return succ;
        }

        // Hỏi finger đó tiếp tục lookup
        return SocketClient.findSuccessor(closest.host(), closest.port(), id);
    }

    // -----------------------------------------------------------------------
    // Chord maintenance (gọi bởi @Scheduled trong DhtScheduler)
    // -----------------------------------------------------------------------

    /**
     * Chord stabilize routine.
     *
     * <p><strong>Mục đích:</strong> Sửa successor pointer và notify successor biết mình.
     *
     * <p><strong>Thuật toán:</strong>
     * <ol>
     *   <li>Lấy predecessor x của successor.</li>
     *   <li>Nếu x nằm giữa mình và successor trên ring → cập nhật successor = x.</li>
     *   <li>Notify successor: "Tôi (self) nghĩ tôi là predecessor của bạn."</li>
     *   <li>Cập nhật successorList từ successor's successor list.</li>
     * </ol>
     */
    public void stabilize() {
        try {
            // Lấy successor còn sống (skip nếu chết)
            NodeInfo succ;
            try {
                succ = getAliveSuccessor();
            } catch (IOException e) {
                LOG.warning("[STABILIZE] Không tìm được successor sống: " + e.getMessage());
                return;
            }

            // Single-node ring: nếu successor là mình
            if (succ.nodeId().equals(self.nodeId())) {
                NodeInfo pred = predecessor.get();
                if (pred != null && !pred.nodeId().equals(self.nodeId())) {
                    fingerTable.setSuccessor(pred);
                }
                return;
            }

            // Hỏi successor: predecessor của mày là ai?
            NodeInfo x;
            try {
                x = SocketClient.getPredecessor(succ.host(), succ.port());
            } catch (IOException e) {
                LOG.warning("[STABILIZE] Không thể hỏi successor " + succ.name() + ": " + e.getMessage());
                // Successor chết → thử dùng successor tiếp theo trong list
                promoteNextSuccessor();
                return;
            }

            // Nếu x nằm giữa mình và successor → x là successor tốt hơn
            if (x != null && HashUtil.inOpenInterval(x.nodeId(), self.nodeId(), succ.nodeId())) {
                fingerTable.setSuccessor(x);
                succ = x;
            }

            // Notify successor biết mình
            try {
                SocketClient.notify(succ.host(), succ.port(), self);
            } catch (IOException e) {
                LOG.warning("[STABILIZE] Không thể notify successor " + succ.name());
            }

            // Cập nhật successorList từ successor của successor
            refreshSuccessorList(succ);

        } catch (Exception e) {
            LOG.log(Level.WARNING, "[STABILIZE] Lỗi không mong đợi", e);
        }
    }

    /**
     * Chord notify: được gọi khi node khác báo "Tôi nghĩ tôi là predecessor của bạn."
     *
     * <p>Chấp nhận candidate nếu nó thực sự nằm giữa predecessor cũ và mình trên ring.
     *
     * @param candidate node đề xuất là predecessor mới
     */
    public void notify(NodeInfo candidate) {
        NodeInfo pred = predecessor.get();
        if (pred == null || HashUtil.inOpenInterval(candidate.nodeId(), pred.nodeId(), self.nodeId())) {
            predecessor.set(candidate);
            LOG.fine(String.format("[DHT] %s: predecessor = %s", self.name(), candidate.name()));
        }
    }

    /**
     * Fix một entry trong finger table (gọi vòng tròn, mỗi lần fix một finger).
     *
     * <p><strong>Fix fingers:</strong> Dần dần sửa toàn bộ finger table sau khi ring thay đổi.
     * Mỗi lần chỉ fix một finger để tránh tạo quá nhiều kết nối TCP cùng lúc.
     */
    private int nextFingerToFix = 1;

    public void fixNextFinger() {
        try {
            int i = nextFingerToFix;
            nextFingerToFix = (nextFingerToFix % FingerTable.SIZE) + 1;

            // Tính điểm bắt đầu của finger i
            BigInteger start = HashUtil.fingerStart(self.nodeId(), i);
            // Tìm successor của điểm đó và cập nhật vào finger table
            NodeInfo f = findSuccessor(start);
            fingerTable.set(i, f);
        } catch (IOException e) {
            LOG.fine("[FIX_FINGERS] " + e.getMessage());
        }
    }

    /**
     * Kiểm tra predecessor có còn sống không.
     *
     * <p><strong>Check predecessor:</strong> Nếu predecessor không phản hồi PING
     * → đặt predecessor = null để ring có thể tự sửa qua stabilize.
     */
    public void checkPredecessor() {
        NodeInfo pred = predecessor.get();
        if (pred == null) return;
        // Gửi PING; nếu không có PONG → predecessor chết
        if (!SocketClient.ping(pred.host(), pred.port())) {
            LOG.warning("[DHT] Predecessor " + pred.name() + " đã chết → đặt predecessor=null");
            predecessor.set(null);
        }
    }

    /**
     * Re-register định kỳ tất cả keys mà node này sở hữu.
     *
     * <p>Phòng trường hợp ring thay đổi cấu trúc (node join/leave) khiến
     * responsible node thay đổi. Re-register đảm bảo key luôn nằm đúng chỗ.
     */
    public void reregisterOwnedKeys() {
        for (Map.Entry<String, NodeInfo> entry : ownedKeys.entrySet()) {
            try {
                handleRegister(entry.getKey(), entry.getValue().host(), entry.getValue().port());
                LOG.fine("[RE-REGISTER] Re-registered '" + entry.getKey() + "'");
            } catch (IOException e) {
                LOG.warning("[RE-REGISTER] Thất bại với key '" + entry.getKey() + "': " + e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Registry operations
    // -----------------------------------------------------------------------

    /**
     * Xử lý lệnh REGISTER: định tuyến tới node chịu trách nhiệm và lưu entry.
     *
     * <p><strong>Quy trình:</strong>
     * <ol>
     *   <li>Tính keyId = SHA-1(serviceName) mod 2^M.</li>
     *   <li>Tìm responsible = findSuccessor(keyId) – O(log N) hop.</li>
     *   <li>Nếu mình là responsible → lưu vào registry cục bộ + replicate tới r successors.</li>
     *   <li>Nếu không → forward REGISTER tới responsible node.</li>
     * </ol>
     *
     * @param serviceName tên dịch vụ logic (ví dụ "alice")
     * @param host        host của service endpoint
     * @param port        port của service endpoint
     */
    public void handleRegister(String serviceName, String host, int port) throws IOException {
        BigInteger keyId = HashUtil.hash(serviceName);
        NodeInfo responsible = findSuccessor(keyId);

        if (responsible.nodeId().equals(self.nodeId())) {
            // Mình là responsible → lưu cục bộ
            registry.put(serviceName, NodeInfo.of(serviceName, host, port));
            LOG.info(String.format("[REGISTRY] Đã đăng ký '%s' -> %s:%d (lưu tại %s, id=%s)",
                    serviceName, host, port, self.name(), keyId));

            // Replicate tới r successors để chịu lỗi
            replicateToSuccessors(serviceName, host, port);
        } else {
            // Forward tới responsible node
            LOG.info(String.format("[REGISTRY] Forward REGISTER '%s' tới %s", serviceName, responsible.name()));
            SocketClient.register(responsible.host(), responsible.port(), serviceName, host, port);
        }
    }

    /**
     * Xử lý lệnh LOOKUP: tìm entry trong DHT.
     *
     * <p><strong>Quy trình:</strong>
     * <ol>
     *   <li>Tính keyId = SHA-1(serviceName) mod 2^M.</li>
     *   <li>Tìm responsible = findSuccessor(keyId).</li>
     *   <li>Nếu mình là responsible → tra cứu registry cục bộ.</li>
     *   <li>Nếu không → forward LOOKUP tới responsible node.</li>
     *   <li>Nếu không tìm thấy → thử trong successorList (fallback cho fault tolerance).</li>
     * </ol>
     *
     * @param serviceName tên dịch vụ cần tra cứu
     * @return NodeInfo của service endpoint, null nếu không tìm thấy
     */
    public NodeInfo handleLookup(String serviceName) throws IOException {
        BigInteger keyId = HashUtil.hash(serviceName);
        NodeInfo responsible = findSuccessor(keyId);

        if (responsible.nodeId().equals(self.nodeId())) {
            // Mình là responsible → tra cứu cục bộ
            RegistryEntry entry = registry.get(serviceName);
            NodeInfo result = (entry == null) ? null : entry.nodeInfo();
            LOG.info(String.format("[LOOKUP] '%s' -> %s (tại %s)",
                    serviceName,
                    result == null ? "NOT_FOUND" : result.host() + ":" + result.port(),
                    self.name()));
            return result;
        } else {
            // Forward tới responsible node
            LOG.info(String.format("[LOOKUP] Forward LOOKUP '%s' tới %s", serviceName, responsible.name()));
            try {
                return SocketClient.lookup(responsible.host(), responsible.port(), serviceName);
            } catch (IOException e) {
                // Responsible node chết → tìm trong successorList (replica)
                LOG.warning("[LOOKUP] Responsible node chết, thử successor list...");
                return lookupFromSuccessorList(serviceName);
            }
        }
    }

    /**
     * Lưu một replica entry vào registry cục bộ (không route, không replicate thêm).
     *
     * <p>Được gọi khi nhận message REPLICATE từ node chịu trách nhiệm.
     */
    public void storeReplica(String serviceName, String host, int port) {
        registry.put(serviceName, NodeInfo.of(serviceName, host, port));
        LOG.fine(String.format("[REPLICA] Lưu replica '%s' -> %s:%d tại %s",
                serviceName, host, port, self.name()));
    }

    /**
     * Trả về tất cả registry entries mà node này giữ, lọc theo phạm vi trách nhiệm.
     *
     * <p>Được gọi khi có node mới join và yêu cầu migrate keys.
     *
     * @param joinerNodeId nodeId của node mới join
     * @return danh sách entries mà node mới nên chịu trách nhiệm
     */
    public List<RegistryEntry> getKeysForMigration(BigInteger joinerNodeId) {
        List<RegistryEntry> result = new ArrayList<>();
        NodeInfo pred = predecessor.get();
        BigInteger predId = (pred == null) ? self.nodeId() : pred.nodeId();

        for (Map.Entry<String, RegistryEntry> e : registry.snapshot().entrySet()) {
            BigInteger keyId = HashUtil.hash(e.getKey());
            // Key thuộc về joiner nếu nằm trong (predId, joinerNodeId]
            if (HashUtil.inHalfOpenInterval(keyId, predId, joinerNodeId)) {
                result.add(e.getValue());
            }
        }
        return result;
    }

    /**
     * Xóa các keys đã migrate đi (sau khi node mới join lấy về).
     *
     * @param joinerNodeId nodeId của node mới join
     */
    public void removeKeysAfterMigration(BigInteger joinerNodeId) {
        NodeInfo pred = predecessor.get();
        BigInteger predId = (pred == null) ? self.nodeId() : pred.nodeId();

        registry.snapshot().forEach((name, entry) -> {
            BigInteger keyId = HashUtil.hash(name);
            if (HashUtil.inHalfOpenInterval(keyId, predId, joinerNodeId)) {
                registry.remove(name);
                LOG.fine("[MIGRATE] Đã xóa key '" + name + "' sau khi migrate");
            }
        });
    }

    /**
     * Cập nhật địa chỉ vật lý của node này (ví dụ khi đổi IP).
     *
     * <p>nodeId KHÔNG đổi → vị trí trên ring không đổi.
     */
    public synchronized void updateAddress(String newHost, int newPort) {
        self = self.withAddress(newHost, newPort);
        for (int i = 1; i <= FingerTable.SIZE; i++) {
            NodeInfo f = fingerTable.get(i);
            if (f != null && f.nodeId().equals(self.nodeId())) {
                fingerTable.set(i, self);
            }
        }
        LOG.info(String.format("[NODE] Cập nhật địa chỉ: %s -> %s:%d", self.name(), newHost, newPort));
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Bắt đầu acceptor thread để chấp nhận kết nối TCP. */
    private void startAcceptor() {
        acceptorThread = new Thread(this::acceptLoop, "acceptor-" + self.name());
        acceptorThread.setDaemon(true);
        acceptorThread.start();
    }

    /** Vòng lặp chấp nhận kết nối và submit tới thread pool. */
    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                connectionPool.submit(new dht.network.ConnectionHandler(clientSocket, this));
            } catch (SocketException e) {
                if (running) LOG.warning("[NODE] ServerSocket lỗi: " + e.getMessage());
            } catch (IOException e) {
                if (running) LOG.log(Level.WARNING, "[NODE] Lỗi accept", e);
            }
        }
    }

    /**
     * Lấy successor đang sống đầu tiên từ finger[1] hoặc successorList.
     *
     * <p><strong>Fault tolerance – successor failover:</strong><br>
     * Nếu primary successor chết → thử successorList[0], [1], ...
     * Nếu tất cả đều chết → trả về chính mình (single-node fallback).
     */
    NodeInfo getAliveSuccessor() throws IOException {
        NodeInfo primary = fingerTable.getSuccessor();

        // Nếu successor là chính mình → single-node ring, OK
        if (primary.nodeId().equals(self.nodeId())) return primary;

        // Kiểm tra primary còn sống không
        if (SocketClient.ping(primary.host(), primary.port())) return primary;

        LOG.warning("[FAILOVER] Successor " + primary.name() + " chết, thử successor list...");

        // Thử từng node trong successorList
        for (NodeInfo backup : successorList) {
            if (backup.nodeId().equals(self.nodeId())) continue;
            if (SocketClient.ping(backup.host(), backup.port())) {
                LOG.info("[FAILOVER] Dùng backup successor: " + backup.name());
                fingerTable.setSuccessor(backup); // cập nhật successor mới
                return backup;
            }
        }

        // Tất cả đều chết → fallback về chính mình
        LOG.warning("[FAILOVER] Không tìm được successor sống, fallback về chính mình");
        fingerTable.setSuccessor(self);
        return self;
    }

    /**
     * Thay thế successor bằng node kế tiếp trong successorList.
     * Được gọi khi stabilize phát hiện successor chết.
     */
    private void promoteNextSuccessor() {
        for (NodeInfo backup : successorList) {
            if (!backup.nodeId().equals(self.nodeId())
                    && SocketClient.ping(backup.host(), backup.port())) {
                LOG.info("[FAILOVER] Promote backup successor: " + backup.name());
                fingerTable.setSuccessor(backup);
                return;
            }
        }
    }

    /**
     * Làm mới successor list bằng cách lấy danh sách từ successor của successor.
     *
     * <p><strong>Successor list maintenance:</strong><br>
     * successorList = [succ, succ.successorList[0], succ.successorList[1], ...]
     * Giới hạn tối đa {@code successorListSize} entries.
     */
    private void refreshSuccessorList(NodeInfo succ) {
        List<NodeInfo> newList = new ArrayList<>();
        newList.add(succ); // Primary successor luôn là đầu danh sách

        try {
            List<NodeInfo> succList = SocketClient.getSuccessorList(succ.host(), succ.port());
            for (NodeInfo n : succList) {
                if (newList.size() >= successorListSize) break;
                if (!n.nodeId().equals(self.nodeId())) newList.add(n);
            }
        } catch (IOException ignored) {
            // Không cần xử lý – successorList sẽ chỉ có primary successor
        }

        successorList.clear();
        successorList.addAll(newList);
    }

    /**
     * Replicate một entry tới các successor trong successorList.
     *
     * <p>Gửi REPLICATE tới tối đa (r-1) successors (không tính node chịu trách nhiệm chính).
     */
    private void replicateToSuccessors(String serviceName, String host, int port) {
        int count = 0;
        for (NodeInfo succ : successorList) {
            if (count >= successorListSize - 1) break;
            if (succ.nodeId().equals(self.nodeId())) continue;
            try {
                SocketClient.replicate(succ.host(), succ.port(), serviceName, host, port);
                count++;
                LOG.fine(String.format("[REPLICA] Replicated '%s' tới %s", serviceName, succ.name()));
            } catch (IOException e) {
                LOG.fine("[REPLICA] Không thể replicate tới " + succ.name());
            }
        }
    }

    /**
     * Tìm entry trong successorList (fallback khi responsible node chết).
     *
     * <p>Các successor đã nhận replica → thử từng node để tìm entry.
     */
    private NodeInfo lookupFromSuccessorList(String serviceName) {
        for (NodeInfo succ : successorList) {
            if (succ.nodeId().equals(self.nodeId())) continue;
            try {
                NodeInfo result = SocketClient.lookup(succ.host(), succ.port(), serviceName);
                if (result != null) {
                    LOG.info("[LOOKUP-FALLBACK] Tìm thấy '" + serviceName + "' tại replica " + succ.name());
                    return result;
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Lấy các keys từ successor sau khi join (key migration).
     *
     * <p>Node mới join gọi hàm này để lấy về các keys mà nó bây giờ chịu trách nhiệm.
     * Successor sẽ trả về danh sách entries và xóa chúng khỏi storage của mình.
     */
    private void migrateKeysFromSuccessor(NodeInfo succ) throws IOException {
        if (succ.nodeId().equals(self.nodeId())) return; // single-node ring

        String raw = SocketClient.migrateKeys(succ.host(), succ.port(), self.nodeId().toString());
        if (raw == null || raw.isBlank()) return;

        int count = 0;
        for (String wire : raw.split("\t")) {
            if (wire.isBlank()) continue;
            try {
                RegistryEntry entry = RegistryEntry.fromWireString(wire);
                registry.put(entry.name(), entry.nodeInfo());
                count++;
            } catch (Exception e) {
                LOG.warning("[MIGRATE] Không thể parse entry: " + wire);
            }
        }
        LOG.info(String.format("[MIGRATE] Đã nhận %d keys từ %s", count, succ.name()));
    }

    /**
     * Đăng ký key vào cache "owned keys" để re-register định kỳ.
     *
     * <p>Gọi từ CLI/client khi người dùng đăng ký một dịch vụ thông qua node này.
     */
    public void trackOwnedKey(String serviceName, String host, int port) {
        ownedKeys.put(serviceName, NodeInfo.of(serviceName, host, port));
    }

    @Override
    public String toString() {
        return String.format("DhtNode{name='%s', id=%s, succ=%s, pred=%s}",
                self.name(), self.nodeId(),
                getSuccessor() == null ? "null" : getSuccessor().name(),
                getPredecessor() == null ? "null" : getPredecessor().name());
    }
}
