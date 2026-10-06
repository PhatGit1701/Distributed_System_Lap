package dht.node;

import dht.routing.HashUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho {@link NodeInfo} và {@link FingerTable} với M=8.
 */
class NodeInfoAndFingerTableTest {

    @BeforeAll
    static void setupM() {
        HashUtil.setM(8); // ID space [0, 255]
    }

    @Test
    void nodeIdDerivedFromName() {
        NodeInfo nodeA = NodeInfo.of("nodeA", "127.0.0.1", 5001);
        assertEquals(HashUtil.hash("nodeA"), nodeA.nodeId());
        // Với M=8: nodeId phải trong [0, 255]
        assertTrue(nodeA.nodeId().compareTo(BigInteger.valueOf(256)) < 0);
        assertTrue(nodeA.nodeId().compareTo(BigInteger.ZERO) >= 0);
    }

    @Test
    void withAddressPreservesNodeId() {
        // nodeId KHÔNG đổi khi node đổi địa chỉ – tính năng cốt lõi của Chord
        NodeInfo original = NodeInfo.of("nodeA", "127.0.0.1", 5001);
        NodeInfo updated  = original.withAddress("10.0.0.1", 9000);
        assertEquals(original.nodeId(), updated.nodeId(), "nodeId phải không đổi sau khi cập nhật địa chỉ");
        assertEquals("10.0.0.1", updated.host());
        assertEquals(9000, updated.port());
        assertEquals("nodeA", updated.name());
    }

    @Test
    void wireRoundTrip() {
        NodeInfo nodeA = NodeInfo.of("nodeA", "127.0.0.1", 5001);
        String wire = nodeA.toWireString();
        NodeInfo parsed = NodeInfo.fromWireString(wire);
        assertEquals(nodeA.name(),   parsed.name());
        assertEquals(nodeA.nodeId(), parsed.nodeId());
        assertEquals(nodeA.host(),   parsed.host());
        assertEquals(nodeA.port(),   parsed.port());
    }

    @Test
    void fingerTableSizeMatchesM() {
        // Với M=8: FingerTable.SIZE phải là 8
        NodeInfo owner = NodeInfo.of("nodeA", "127.0.0.1", 5001);
        new FingerTable(owner); // constructor cập nhật SIZE
        assertEquals(8, FingerTable.SIZE);
    }

    @Test
    void fingerTableInitialisedToOwner() {
        NodeInfo owner = NodeInfo.of("nodeA", "127.0.0.1", 5001);
        FingerTable ft = new FingerTable(owner);
        // Tất cả fingers ban đầu trỏ về chính mình (single-node ring)
        for (int i = 1; i <= FingerTable.SIZE; i++) {
            assertEquals(owner.nodeId(), ft.get(i).nodeId(),
                    "finger[" + i + "] phải trỏ về owner ban đầu");
        }
    }

    @Test
    void closestPrecedingFingerReturnsSelfWhenOnlyOneNode() {
        NodeInfo owner = NodeInfo.of("nodeA", "127.0.0.1", 5001);
        FingerTable ft = new FingerTable(owner);
        BigInteger target = HashUtil.hash("someKey");
        // Chỉ có 1 node → closest preceding finger phải là chính mình
        NodeInfo closest = ft.closestPrecedingFinger(target);
        assertEquals(owner.nodeId(), closest.nodeId());
    }

    @Test
    void fingerTableCanBeUpdated() {
        NodeInfo owner = NodeInfo.of("nodeA", "127.0.0.1", 5001);
        NodeInfo nodeB = NodeInfo.of("nodeB", "127.0.0.1", 5002);
        FingerTable ft = new FingerTable(owner);
        // Cập nhật finger[1] = nodeB → getSuccessor() phải trả về nodeB
        ft.set(1, nodeB);
        assertEquals(nodeB.nodeId(), ft.get(1).nodeId());
        assertEquals(nodeB.nodeId(), ft.getSuccessor().nodeId());
    }

    @Test
    void registryEntryWireRoundTrip() {
        // Test RegistryEntry wire format
        NodeInfo svc = NodeInfo.of("alice", "192.168.1.1", 6001);
        dht.registry.RegistryEntry entry = new dht.registry.RegistryEntry("alice", svc);
        String wire = entry.toWireString();
        dht.registry.RegistryEntry parsed = dht.registry.RegistryEntry.fromWireString(wire);
        assertEquals(entry.name(), parsed.name());
        assertEquals(svc.host(), parsed.nodeInfo().host());
        assertEquals(svc.port(), parsed.nodeInfo().port());
    }
}
