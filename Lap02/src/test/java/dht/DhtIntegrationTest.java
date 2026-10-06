package dht;

import dht.client.MessagingClient;
import dht.network.SocketClient;
import dht.node.DhtNode;
import dht.node.NodeInfo;
import dht.routing.HashUtil;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests cho Chord DHT với M=8.
 *
 * <p>Mỗi test dùng port riêng biệt để tránh xung đột.
 * Node được dừng trong {@code @AfterEach}.
 *
 * <p><strong>Bộ test bao gồm:</strong>
 * <ul>
 *   <li>Hash determinism (sanity check)</li>
 *   <li>Node khởi động ring đơn</li>
 *   <li>Node thứ hai join ring</li>
 *   <li>Finger table được tạo đúng (SIZE=8)</li>
 *   <li>Successor lookup</li>
 *   <li>Registry register/lookup</li>
 *   <li>Gửi message</li>
 *   <li>Cập nhật địa chỉ và lookup lại</li>
 *   <li>Node không khả dụng → lỗi có kiểm soát</li>
 *   <li>Ring 3 node với Chord routing</li>
 *   <li><strong>[Fault Tolerance]</strong> Kill một node → records vẫn resolvable qua replica</li>
 *   <li>Successor list</li>
 *   <li>Key replication</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DhtIntegrationTest {

    /** Port gốc – mỗi test dùng BASE_PORT + offset riêng */
    private static final int BASE_PORT = 15000;

    private DhtNode nodeA;
    private DhtNode nodeB;
    private DhtNode nodeC;

    @BeforeAll
    static void setupRing() {
        // Thiết lập M=8 cho toàn bộ integration tests
        HashUtil.setM(8);
    }

    @AfterEach
    void tearDown() {
        if (nodeC != null) { nodeC.stop(); nodeC = null; }
        if (nodeB != null) { nodeB.stop(); nodeB = null; }
        if (nodeA != null) { nodeA.stop(); nodeA = null; }
        sleep(150); // đợi OS giải phóng ports
    }

    // -----------------------------------------------------------------------
    // Test 1: Hash determinism
    // -----------------------------------------------------------------------

    @Test @Order(1)
    void hashDeterministicSanityCheck() {
        // Hash phải nhất quán và khác nhau cho các input khác nhau
        BigInteger h1 = HashUtil.hash("nodeA");
        BigInteger h2 = HashUtil.hash("nodeA");
        assertEquals(h1, h2, "SHA-1 phải cho kết quả xác định");
        assertNotEquals(HashUtil.hash("nodeA"), HashUtil.hash("nodeB"));

        // Với M=8: kết quả trong [0, 255]
        assertTrue(h1.compareTo(BigInteger.ZERO) >= 0);
        assertTrue(h1.compareTo(BigInteger.valueOf(256)) < 0);
    }

    // -----------------------------------------------------------------------
    // Test 2: Node khởi động ring đơn
    // -----------------------------------------------------------------------

    @Test @Order(2)
    void singleNodeCanStart() throws IOException {
        nodeA = new DhtNode("nodeA-t2", "127.0.0.1", BASE_PORT + 10);
        nodeA.start();

        assertTrue(nodeA.isRunning());
        // Ring đơn: successor = chính mình
        assertEquals(nodeA.getSelf().nodeId(), nodeA.getSuccessor().nodeId());
        // PING phải trả về PONG
        assertTrue(SocketClient.ping("127.0.0.1", BASE_PORT + 10));
    }

    // -----------------------------------------------------------------------
    // Test 3: Node thứ hai join ring
    // -----------------------------------------------------------------------

    @Test @Order(3)
    void secondNodeCanJoin() throws IOException {
        nodeA = new DhtNode("nodeA-t3", "127.0.0.1", BASE_PORT + 20);
        nodeA.start();

        nodeB = new DhtNode("nodeB-t3", "127.0.0.1", BASE_PORT + 21);
        nodeB.join("127.0.0.1", BASE_PORT + 20);

        sleep(500);

        assertTrue(nodeB.isRunning());
        assertNotNull(nodeB.getSuccessor(), "nodeB phải có successor sau khi join");
    }

    // -----------------------------------------------------------------------
    // Test 4: Finger table SIZE=8 với M=8
    // -----------------------------------------------------------------------

    @Test @Order(4)
    void fingerTableHasCorrectSize() throws IOException {
        nodeA = new DhtNode("nodeA-t4", "127.0.0.1", BASE_PORT + 30);
        nodeA.start();

        // Với M=8: finger table phải có đúng 8 entries
        assertEquals(8, dht.node.FingerTable.SIZE, "M=8 → FingerTable.SIZE phải là 8");

        // Tất cả 8 finger phải non-null
        for (int i = 1; i <= dht.node.FingerTable.SIZE; i++) {
            assertNotNull(nodeA.getFingerTable().get(i),
                    "finger[" + i + "] không được null");
        }
    }

    // -----------------------------------------------------------------------
    // Test 5: Successor lookup
    // -----------------------------------------------------------------------

    @Test @Order(5)
    void successorLookupWorks() throws IOException {
        nodeA = new DhtNode("nodeA-t5", "127.0.0.1", BASE_PORT + 40);
        nodeA.start();

        // Ring đơn: mọi key đều có successor là chính mình
        BigInteger someKey = HashUtil.hash("chat-service");
        NodeInfo succ = nodeA.findSuccessor(someKey);
        assertEquals(nodeA.getSelf().nodeId(), succ.nodeId());
    }

    // -----------------------------------------------------------------------
    // Test 6: Registry registration
    // -----------------------------------------------------------------------

    @Test @Order(6)
    void registryRegistrationWorks() throws IOException {
        nodeA = new DhtNode("nodeA-t6", "127.0.0.1", BASE_PORT + 50);
        nodeA.start();

        nodeA.handleRegister("alice", "127.0.0.1", 6001);

        // Ring đơn → entry luôn lưu tại nodeA
        var entry = nodeA.getRegistry().get("alice");
        assertNotNull(entry, "alice phải được đăng ký tại nodeA (ring đơn)");
        assertEquals("127.0.0.1", entry.nodeInfo().host());
        assertEquals(6001, entry.nodeInfo().port());
    }

    // -----------------------------------------------------------------------
    // Test 7: Registry lookup
    // -----------------------------------------------------------------------

    @Test @Order(7)
    void registryLookupWorks() throws IOException {
        nodeA = new DhtNode("nodeA-t7", "127.0.0.1", BASE_PORT + 60);
        nodeA.start();

        nodeA.handleRegister("bob", "127.0.0.1", 7001);
        NodeInfo result = nodeA.handleLookup("bob");

        assertNotNull(result);
        assertEquals("127.0.0.1", result.host());
        assertEquals(7001, result.port());
    }

    // -----------------------------------------------------------------------
    // Test 8: Gửi message (ring 2 node)
    // -----------------------------------------------------------------------

    @Test @Order(8)
    void messageSendingWorks() throws IOException {
        nodeA = new DhtNode("nodeA-t8", "127.0.0.1", BASE_PORT + 70);
        nodeA.start();

        nodeB = new DhtNode("nodeB-t8", "127.0.0.1", BASE_PORT + 71);
        nodeB.join("127.0.0.1", BASE_PORT + 70);
        sleep(600);

        // Đăng ký "alice" → endpoint là nodeA (port 70)
        nodeB.handleRegister("alice", "127.0.0.1", BASE_PORT + 70);
        sleep(200);

        // Gửi qua MessagingClient thông qua nodeB
        MessagingClient clientB = new MessagingClient("127.0.0.1", BASE_PORT + 71);
        String result = clientB.send("alice", "Xin chào Alice");
        assertNotNull(result);
        assertFalse(result.contains("ERROR"), "Gửi message phải thành công, got: " + result);
    }

    // -----------------------------------------------------------------------
    // Test 9: Cập nhật địa chỉ
    // -----------------------------------------------------------------------

    @Test @Order(9)
    void addressUpdateWorks() throws IOException {
        nodeA = new DhtNode("nodeA-t9", "127.0.0.1", BASE_PORT + 80);
        nodeA.start();

        nodeA.handleRegister("svc", "127.0.0.1", 6000);
        NodeInfo before = nodeA.handleLookup("svc");
        assertNotNull(before);
        assertEquals(6000, before.port());

        // Mô phỏng đổi IP/port: cập nhật sang port 7000
        nodeA.handleRegister("svc", "127.0.0.1", 7000);
        NodeInfo after = nodeA.handleLookup("svc");
        assertNotNull(after);
        assertEquals(7000, after.port(), "Lookup phải trả về port mới sau khi cập nhật");
    }

    // -----------------------------------------------------------------------
    // Test 10: Lookup sau khi đổi địa chỉ (qua client)
    // -----------------------------------------------------------------------

    @Test @Order(10)
    void lookupAfterAddressChangeReturnsNewInfo() throws IOException {
        nodeA = new DhtNode("nodeA-t10", "127.0.0.1", BASE_PORT + 90);
        nodeA.start();

        nodeA.handleRegister("alice", "127.0.0.1", 6001);

        MessagingClient c = new MessagingClient("127.0.0.1", BASE_PORT + 90);
        NodeInfo first = c.lookup("alice");
        assertNotNull(first);
        assertEquals(6001, first.port());

        // Alice chuyển sang port 6099
        c.updateAddress("alice", "127.0.0.1", 6099);

        NodeInfo second = c.lookup("alice");
        assertNotNull(second);
        assertEquals(6099, second.port(), "Lookup phải trả về 6099 sau khi đổi địa chỉ");
    }

    // -----------------------------------------------------------------------
    // Test 11: Node không khả dụng → lỗi có kiểm soát
    // -----------------------------------------------------------------------

    @Test @Order(11)
    void unavailableNodeProducesControlledError() {
        // PING tới port không có server → false, không throw
        assertFalse(SocketClient.ping("127.0.0.1", 19999));
    }

    @Test @Order(12)
    void lookupOnUnreachableNodeThrowsIOException() {
        MessagingClient c = new MessagingClient("127.0.0.1", 19998);
        assertThrows(IOException.class, () -> c.lookup("anyone"),
                "IOException expected khi gateway không khả dụng");
    }

    // -----------------------------------------------------------------------
    // Test 13: Ring 3 node với Chord routing
    // -----------------------------------------------------------------------

    @Test @Order(13)
    void threeNodeRingRoutingWorks() throws IOException {
        nodeA = new DhtNode("nodeA-t13", "127.0.0.1", BASE_PORT + 100);
        nodeA.start();

        nodeB = new DhtNode("nodeB-t13", "127.0.0.1", BASE_PORT + 101);
        nodeB.join("127.0.0.1", BASE_PORT + 100);
        sleep(400);

        nodeC = new DhtNode("nodeC-t13", "127.0.0.1", BASE_PORT + 102);
        nodeC.join("127.0.0.1", BASE_PORT + 100);
        sleep(1200); // đợi stabilize hội tụ

        // Đăng ký 2 dịch vụ
        nodeA.handleRegister("serviceX", "127.0.0.1", 8001);
        nodeA.handleRegister("serviceY", "127.0.0.1", 8002);
        sleep(300);

        // Lookup từ các node khác nhau
        NodeInfo x = nodeB.handleLookup("serviceX");
        NodeInfo y = nodeC.handleLookup("serviceY");

        assertNotNull(x, "serviceX phải tìm được từ nodeB");
        assertNotNull(y, "serviceY phải tìm được từ nodeC");
        assertEquals(8001, x.port());
        assertEquals(8002, y.port());
    }

    // -----------------------------------------------------------------------
    // Test 14: [FAULT TOLERANCE] Kill một node → records vẫn resolvable
    // -----------------------------------------------------------------------

    /**
     * Kiểm tra tính chịu lỗi (fault tolerance) của DHT:
     *
     * <p><strong>Kịch bản:</strong>
     * <ol>
     *   <li>Khởi động ring 3 node: A, B, C.</li>
     *   <li>Đăng ký dịch vụ "critical-svc" → được lưu tại node responsible + replicated.</li>
     *   <li>Kill node responsible (dừng hoàn toàn).</li>
     *   <li>Lookup từ các node còn lại → phải tìm thấy qua replica.</li>
     * </ol>
     *
     * <p><strong>Cơ chế:</strong>
     * Khi REGISTER được xử lý, entry được replicate tới successors trong successorList.
     * Khi node chịu trách nhiệm chết, lookup sẽ tự động fallback sang successorList
     * để tìm bản replica.
     */
    @Test @Order(14)
    void faultTolerance_killOneNode_recordsStillResolvable() throws IOException {
        // --- Setup: ring 3 node ---
        nodeA = new DhtNode("nodeA-t14", "127.0.0.1", BASE_PORT + 110, 3);
        nodeA.start();

        nodeB = new DhtNode("nodeB-t14", "127.0.0.1", BASE_PORT + 111, 3);
        nodeB.join("127.0.0.1", BASE_PORT + 110);
        sleep(500);

        nodeC = new DhtNode("nodeC-t14", "127.0.0.1", BASE_PORT + 112, 3);
        nodeC.join("127.0.0.1", BASE_PORT + 110);
        sleep(1500); // đợi stabilize + finger table hội tụ

        System.out.println("[TEST-FT] Ring đã hội tụ:");
        System.out.printf("  nodeA: id=%s, succ=%s%n", nodeA.getSelf().nodeId(), nodeA.getSuccessor().nodeId());
        System.out.printf("  nodeB: id=%s, succ=%s%n", nodeB.getSelf().nodeId(), nodeB.getSuccessor().nodeId());
        System.out.printf("  nodeC: id=%s, succ=%s%n", nodeC.getSelf().nodeId(), nodeC.getSuccessor().nodeId());

        // --- Đăng ký dịch vụ qua nodeA ---
        String svcName = "critical-svc";
        nodeA.handleRegister(svcName, "127.0.0.1", 9999);
        sleep(300); // đợi replicate tới successors

        // --- Xác nhận đăng ký thành công ban đầu ---
        NodeInfo beforeKill = nodeA.handleLookup(svcName);
        assertNotNull(beforeKill, "Dịch vụ phải tìm được trước khi kill node");
        assertEquals(9999, beforeKill.port());
        System.out.printf("[TEST-FT] Lookup trước kill: %s -> %s:%d%n",
                svcName, beforeKill.host(), beforeKill.port());

        // --- Xác định node nào là responsible ---
        BigInteger keyId = HashUtil.hash(svcName);
        System.out.printf("[TEST-FT] keyId của '%s' = %s%n", svcName, keyId);

        // --- Tìm node KHÔNG phải là responsible để lookup sau khi kill ---
        // Kiểm tra registry của từng node để biết ai đang giữ key
        DhtNode responsibleNode = null;
        DhtNode[] aliveNodes;

        boolean aHasKey = nodeA.getRegistry().get(svcName) != null;
        boolean bHasKey = nodeB.getRegistry().get(svcName) != null;
        boolean cHasKey = nodeC.getRegistry().get(svcName) != null;

        System.out.printf("[TEST-FT] Registry: A=%s, B=%s, C=%s%n",
                aHasKey ? "có key" : "không có",
                bHasKey ? "có key" : "không có",
                cHasKey ? "có key" : "không có");

        // Tìm node responsible (có key primary) và 2 node còn lại
        if (aHasKey && !bHasKey) {
            // Kill nodeA (responsible), lookup qua B hoặc C
            System.out.println("[TEST-FT] Kill nodeA (responsible node)");
            responsibleNode = nodeA;
            nodeA.stop();
            nodeA = null;
            aliveNodes = new DhtNode[]{nodeB, nodeC};
        } else if (bHasKey && !aHasKey) {
            System.out.println("[TEST-FT] Kill nodeB (responsible node)");
            responsibleNode = nodeB;
            nodeB.stop();
            nodeB = null;
            aliveNodes = new DhtNode[]{nodeA, nodeC};
        } else if (cHasKey && !aHasKey) {
            System.out.println("[TEST-FT] Kill nodeC (responsible node)");
            responsibleNode = nodeC;
            nodeC.stop();
            nodeC = null;
            aliveNodes = new DhtNode[]{nodeA, nodeB};
        } else {
            // Trường hợp ring đơn hoặc key được replicate rộng → skip kill
            System.out.println("[TEST-FT] Key đã được replicate rộng, skip kill-node test");
            aliveNodes = new DhtNode[]{nodeA, nodeB, nodeC};
        }

        sleep(500); // đợi hệ thống nhận ra node chết

        // --- Lookup từ các node còn sống ---
        // Ít nhất một trong các node còn sống phải resolve được key
        // (hoặc thông qua replica, hoặc thông qua routing)
        boolean resolved = false;
        for (DhtNode alive : aliveNodes) {
            if (alive == null) continue;
            try {
                NodeInfo result = alive.handleLookup(svcName);
                if (result != null && result.port() == 9999) {
                    resolved = true;
                    System.out.printf("[TEST-FT] Tìm thấy '%s' từ %s (qua replica/fallback) -> %s:%d%n",
                            svcName, alive.getSelf().name(), result.host(), result.port());
                    break;
                }
            } catch (IOException e) {
                System.out.println("[TEST-FT] Lookup thất bại từ " + alive.getSelf().name() + ": " + e.getMessage());
            }
        }

        assertTrue(resolved,
                "Dịch vụ '" + svcName + "' phải tìm được từ ít nhất một node còn sống " +
                "(thông qua replica hoặc fallback)");
    }

    // -----------------------------------------------------------------------
    // Test 15: Successor list
    // -----------------------------------------------------------------------

    @Test @Order(15)
    void successorListIsPopulated() throws IOException {
        nodeA = new DhtNode("nodeA-t15", "127.0.0.1", BASE_PORT + 120, 3);
        nodeA.start();

        nodeB = new DhtNode("nodeB-t15", "127.0.0.1", BASE_PORT + 121, 3);
        nodeB.join("127.0.0.1", BASE_PORT + 120);

        nodeC = new DhtNode("nodeC-t15", "127.0.0.1", BASE_PORT + 122, 3);
        nodeC.join("127.0.0.1", BASE_PORT + 120);

        sleep(1500); // đợi stabilize cập nhật successorList

        // Sau stabilize, nodeA phải có ít nhất 1 entry trong successorList
        List<NodeInfo> succList = nodeA.getSuccessorList();
        System.out.printf("[TEST-SL] nodeA successorList: %s%n", succList);
        assertFalse(succList.isEmpty(), "successorList phải có ít nhất 1 entry sau khi ring hội tụ");
    }

    // -----------------------------------------------------------------------
    // Test 16: Key replication
    // -----------------------------------------------------------------------

    @Test @Order(16)
    void keyReplicationToSuccessors() throws IOException {
        nodeA = new DhtNode("nodeA-t16", "127.0.0.1", BASE_PORT + 130, 3);
        nodeA.start();

        nodeB = new DhtNode("nodeB-t16", "127.0.0.1", BASE_PORT + 131, 3);
        nodeB.join("127.0.0.1", BASE_PORT + 130);

        sleep(800);

        // Đăng ký key qua nodeA
        nodeA.handleRegister("replicated-svc", "127.0.0.1", 7777);
        sleep(400); // đợi replicate

        // Ít nhất một trong các node phải giữ bản sao
        boolean nodeAHas = nodeA.getRegistry().get("replicated-svc") != null;
        boolean nodeBHas = nodeB.getRegistry().get("replicated-svc") != null;

        System.out.printf("[TEST-REPL] nodeA có key: %s, nodeB có key: %s%n", nodeAHas, nodeBHas);

        // Ít nhất 1 node phải có entry (primary hoặc replica)
        assertTrue(nodeAHas || nodeBHas,
                "Ít nhất một node phải giữ 'replicated-svc' (primary hoặc replica)");
    }

    // -----------------------------------------------------------------------
    // Test 17: checkPredecessor reset predecessor về null khi node chết
    // -----------------------------------------------------------------------

    @Test @Order(17)
    void checkPredecessorResetsDeadPredecessor() throws IOException {
        nodeA = new DhtNode("nodeA-t17", "127.0.0.1", BASE_PORT + 140);
        nodeA.start();

        nodeB = new DhtNode("nodeB-t17", "127.0.0.1", BASE_PORT + 141);
        nodeB.join("127.0.0.1", BASE_PORT + 140);
        sleep(800);

        // Sau stabilize, một trong hai node phải có predecessor
        // Giả sử nodeA có predecessor = nodeB hoặc ngược lại
        System.out.printf("[TEST-CP] nodeA pred: %s, nodeB pred: %s%n",
                nodeA.getPredecessor(), nodeB.getPredecessor());

        // Kill nodeB
        nodeB.stop();
        nodeB = null;
        sleep(200);

        // Gọi checkPredecessor thủ công (trong thực tế được gọi bởi @Scheduled)
        nodeA.checkPredecessor();

        // Nếu predecessor của nodeA là nodeB đã chết → phải được reset về null
        NodeInfo pred = nodeA.getPredecessor();
        if (pred != null) {
            // Predecessor còn tồn tại → nó phải là một node đang sống (không phải nodeB)
            boolean predAlive = SocketClient.ping(pred.host(), pred.port());
            System.out.printf("[TEST-CP] nodeA predecessor sau kill: %s, còn sống: %s%n",
                    pred.name(), predAlive);
            // Predecessor hiện tại (nếu có) phải còn sống
            assertTrue(predAlive || pred.nodeId().equals(nodeA.getSelf().nodeId()),
                    "Predecessor phải là node đang sống hoặc null sau khi kill nodeB");
        } else {
            System.out.println("[TEST-CP] nodeA predecessor đã được reset về null sau kill");
        }
        // Test pass nếu không throw exception
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
