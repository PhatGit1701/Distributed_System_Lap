package vn.edu.distributed;

public final class Logger {
    private Logger() {}
    public static synchronized void log(String line) {
        System.out.println(line);
    }
}
