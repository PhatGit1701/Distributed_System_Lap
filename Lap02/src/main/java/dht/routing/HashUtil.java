package dht.routing;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Tiện ích tính hash SHA-1 và các phép toán trên vòng Chord.
 *
 * <p><strong>Vòng Chord (Chord Ring):</strong><br>
 * Mỗi node và mỗi key được ánh xạ vào một điểm trên vòng tròn kích thước 2^M.
 * Với M=8 (mặc định): không gian ID là [0, 255] – nhỏ, dễ đọc trong log.
 *
 * <p>Hàm hash: {@code id = SHA-1(name) mod 2^M}
 * → dùng modulo để thu gọn vào khoảng [0, 2^M).
 */
public final class HashUtil {

    /**
     * Số bit của vòng ring (M).
     *
     * <p>Mặc định M=8 → ID space [0, 255].
     * Có thể override khi khởi tạo node qua {@link #setM(int)}.
     * Thread-safe: chỉ set một lần khi app khởi động.
     */
    private static volatile int M = 8;

    /** Kích thước vòng ring = 2^M. Được tính lại khi M thay đổi. */
    private static volatile BigInteger RING_SIZE = BigInteger.TWO.pow(M);

    private HashUtil() {}

    /**
     * Thiết lập kích thước ring. Gọi một lần duy nhất khi app khởi động
     * (thường trong {@code DhtNodeConfig}).
     *
     * @param m số bit (ví dụ 8 → ring [0,255])
     */
    public static void setM(int m) {
        M = m;
        RING_SIZE = BigInteger.TWO.pow(m);
    }

    /** @return số bit M hiện tại */
    public static int getM() { return M; }

    /** @return kích thước vòng ring 2^M */
    public static BigInteger getRingSize() { return RING_SIZE; }

    /**
     * Tính hash SHA-1 của chuỗi đầu vào rồi modulo 2^M.
     *
     * <p><strong>Bước 1 – Hash:</strong> SHA-1 tạo ra 160 bit.<br>
     * <strong>Bước 2 – Modulo:</strong> Thu gọn vào [0, 2^M) để phù hợp ring size M bit.
     *
     * @param input chuỗi cần hash (UTF-8)
     * @return giá trị trong [0, 2^M)
     */
    public static BigInteger hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // signum=1 đảm bảo số dương
            return new BigInteger(1, digest).mod(RING_SIZE);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 được đảm bảo có trong Java spec – không thể xảy ra
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    /**
     * Tính điểm bắt đầu của finger thứ i trong bảng finger table.
     *
     * <p>Công thức Chord: {@code start_i = (nodeId + 2^(i-1)) mod 2^M}, i ∈ [1..M].
     *
     * @param nodeId ID của node
     * @param i      chỉ số finger (1-based)
     * @return điểm bắt đầu của finger i
     */
    public static BigInteger fingerStart(BigInteger nodeId, int i) {
        BigInteger offset = BigInteger.TWO.pow(i - 1);
        return nodeId.add(offset).mod(RING_SIZE);
    }

    /**
     * Kiểm tra {@code id} có nằm trong khoảng mở (from, to) trên vòng ring không.
     *
     * <p>Xử lý wrap-around: nếu from > to thì khoảng bao gồm 0.
     * Ví dụ (M=8): from=250, to=10 → id=255 hoặc id=5 đều thỏa mãn.
     */
    public static boolean inOpenInterval(BigInteger id, BigInteger from, BigInteger to) {
        if (from.compareTo(to) < 0) {
            // Khoảng thẳng: from < id < to
            return id.compareTo(from) > 0 && id.compareTo(to) < 0;
        } else if (from.compareTo(to) > 0) {
            // Wrap-around: id > from HOẶC id < to
            return id.compareTo(from) > 0 || id.compareTo(to) < 0;
        } else {
            // from == to: toàn bộ vòng ring (single-node ring)
            return true;
        }
    }

    /**
     * Kiểm tra {@code id} có nằm trong khoảng nửa mở (from, to] không.
     *
     * <p>Giống {@link #inOpenInterval} nhưng bao gồm cả {@code to}.
     * Dùng khi kiểm tra: "key có thuộc về successor này không?"
     */
    public static boolean inHalfOpenInterval(BigInteger id, BigInteger from, BigInteger to) {
        return id.equals(to) || inOpenInterval(id, from, to);
    }
}
