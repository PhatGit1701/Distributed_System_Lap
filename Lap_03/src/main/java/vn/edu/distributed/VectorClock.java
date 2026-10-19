package vn.edu.distributed;

import java.util.Arrays;

public final class VectorClock {
    private final int ownerId;
    private final int[] vector;

    public VectorClock(int size, int ownerId) {
        if (ownerId < 0 || ownerId >= size)
            throw new IllegalArgumentException("ID không hợp lệ");
        this.ownerId = ownerId;
        this.vector = new int[size];
    }

    // Local hoặc send: tăng phần tử của mình.
    public synchronized int[] tick() {
        vector[ownerId]++;
        return vector.clone();
    }

    // Receive: max từng phần tử trước, tăng phần tử của mình sau.
    public synchronized int[] merge(int[] received) {
        checkSize(vector, received);
        for (int i = 0; i < vector.length; i++)
            vector[i] = Math.max(vector[i], received[i]);
        vector[ownerId]++;
        return vector.clone();
    }

    public synchronized int[] snapshot() { return vector.clone(); }

    public static boolean leq(int[] a, int[] b) {
        checkSize(a, b);
        for (int i = 0; i < a.length; i++)
            if (a[i] > b[i]) return false;
        return true;
    }

    public static boolean happensBefore(int[] a, int[] b) {
        return leq(a, b) && !Arrays.equals(a, b);
    }

    // Hai vector không so sánh được theo <= là concurrent.
    // Hai vector bằng nhau không concurrent.
    public static boolean isConcurrent(int[] a, int[] b) {
        return !leq(a, b) && !leq(b, a);
    }

    private static void checkSize(int[] a, int[] b) {
        if (a.length != b.length)
            throw new IllegalArgumentException("Vector phải cùng kích thước");
    }
}
