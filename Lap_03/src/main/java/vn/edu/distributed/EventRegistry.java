package vn.edu.distributed;

import java.util.Arrays;

// Bộ quan sát lưu sự kiện mới nhất của từng tiến trình.
public final class EventRegistry {
    private final int[][] latestVectors;

    public EventRegistry(int size) {
        latestVectors = new int[size][];
    }

    // Khóa cả in log và kiểm tra để các thread không chen vào nhau.
    public synchronized void record(int id, String event, int time, int[] vector) {
        Logger.log("[P" + id + "] " + event + " | Lamport: " + time
                + " | Vector: " + Arrays.toString(vector));
        for (int other = 0; other < latestVectors.length; other++) {
            if (other == id || latestVectors[other] == null) continue;
            if (VectorClock.isConcurrent(vector, latestVectors[other])) {
                Logger.log("[P" + id + "] CONFLICT DETECTED with P" + other
                        + "! Vector " + compact(vector) + " || " + compact(latestVectors[other]));
            }
        }
        latestVectors[id] = vector.clone();
    }

    private static String compact(int[] v) {
        return Arrays.toString(v).replace(" ", "");
    }
}
