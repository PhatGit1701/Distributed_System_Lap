package dht.routing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho {@link HashUtil} với M=8.
 *
 * <p>Với M=8: ID space là [0, 255]. Tất cả hashes được modulo 256.
 */
class HashUtilTest {

    @BeforeAll
    static void setupM() {
        // Đặt M=8 cho tất cả tests (ID space [0, 255])
        HashUtil.setM(8);
    }

    // -----------------------------------------------------------------------
    // Test hash
    // -----------------------------------------------------------------------

    @Test
    void hashIsDeterministic() {
        // Cùng input → cùng output (tính xác định)
        BigInteger h1 = HashUtil.hash("nodeA");
        BigInteger h2 = HashUtil.hash("nodeA");
        assertEquals(h1, h2, "Hash phải cho kết quả nhất quán");
    }

    @Test
    void hashIsUniqueForDifferentInputs() {
        // Input khác nhau → output khác nhau (với xác suất cao)
        assertNotEquals(HashUtil.hash("nodeA"), HashUtil.hash("nodeB"));
        assertNotEquals(HashUtil.hash("alice"), HashUtil.hash("bob"));
    }

    @Test
    void hashFitsInRingM8() {
        // Với M=8: tất cả hashes phải trong [0, 255]
        String[] inputs = {"nodeA", "nodeB", "alice", "bob", "serviceX", "chat"};
        for (String s : inputs) {
            BigInteger h = HashUtil.hash(s);
            assertTrue(h.compareTo(BigInteger.ZERO) >= 0, s + ": hash phải >= 0");
            assertTrue(h.compareTo(BigInteger.valueOf(256)) < 0, s + ": hash phải < 256");
        }
    }

    @Test
    void ringSize() {
        assertEquals(BigInteger.valueOf(256), HashUtil.getRingSize(), "2^8 = 256");
    }

    // -----------------------------------------------------------------------
    // Test inOpenInterval
    // -----------------------------------------------------------------------

    @Test
    void inOpenIntervalNormalRange() {
        BigInteger from = BigInteger.valueOf(10);
        BigInteger to   = BigInteger.valueOf(50);
        assertTrue(HashUtil.inOpenInterval(BigInteger.valueOf(30), from, to));
        assertFalse(HashUtil.inOpenInterval(BigInteger.valueOf(10), from, to)); // exclusive
        assertFalse(HashUtil.inOpenInterval(BigInteger.valueOf(50), from, to)); // exclusive
    }

    @Test
    void inOpenIntervalWrapAround() {
        // Wrap-around: (230, 20) trong ring 256
        BigInteger from = BigInteger.valueOf(230);
        BigInteger to   = BigInteger.valueOf(20);
        assertTrue(HashUtil.inOpenInterval(BigInteger.valueOf(250), from, to));
        assertTrue(HashUtil.inOpenInterval(BigInteger.valueOf(5), from, to));
        assertFalse(HashUtil.inOpenInterval(BigInteger.valueOf(100), from, to));
    }

    @Test
    void inHalfOpenIntervalIncludesToBoundary() {
        BigInteger from = BigInteger.valueOf(10);
        BigInteger to   = BigInteger.valueOf(50);
        assertTrue(HashUtil.inHalfOpenInterval(BigInteger.valueOf(50), from, to)); // inclusive
        assertFalse(HashUtil.inHalfOpenInterval(BigInteger.valueOf(10), from, to)); // exclusive
    }

    // -----------------------------------------------------------------------
    // Test fingerStart
    // -----------------------------------------------------------------------

    @Test
    void fingerStartFormula() {
        // nodeId=5, i=1 → start = (5 + 2^0) mod 256 = 6
        BigInteger nodeId = BigInteger.valueOf(5);
        assertEquals(BigInteger.valueOf(6),  HashUtil.fingerStart(nodeId, 1));
        assertEquals(BigInteger.valueOf(7),  HashUtil.fingerStart(nodeId, 2)); // +2
        assertEquals(BigInteger.valueOf(9),  HashUtil.fingerStart(nodeId, 3)); // +4
        assertEquals(BigInteger.valueOf(13), HashUtil.fingerStart(nodeId, 4)); // +8
    }

    @Test
    void fingerStartWrapAround() {
        // nodeId=250, i=1 → (250+1)%256=251; i=4 → (250+8)%256=2
        BigInteger nodeId = BigInteger.valueOf(250);
        assertEquals(BigInteger.valueOf(251), HashUtil.fingerStart(nodeId, 1));
        assertEquals(BigInteger.valueOf(2),   HashUtil.fingerStart(nodeId, 4)); // 258%256=2
    }
}
