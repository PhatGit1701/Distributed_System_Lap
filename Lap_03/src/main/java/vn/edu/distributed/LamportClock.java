package vn.edu.distributed;

public final class LamportClock {
    private int time;
    public synchronized int tick() { return ++time; }
    public synchronized int update(int received) {
        time = Math.max(time, received) + 1;
        return time;
    }
    public synchronized int get() { return time; }
}
