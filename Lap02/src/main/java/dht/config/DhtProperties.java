package dht.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình DHT đọc từ application.yml với prefix "dht".
 *
 * <p>Ví dụ trong application.yml:
 * <pre>
 * dht:
 *   name: nodeA
 *   port: 5001
 *   m: 8
 *   successor-list-size: 3
 *   bootstrap:
 *     host: 127.0.0.1
 *     port: 5000
 * </pre>
 *
 * <p>Spring Boot tự động bind các giá trị vào các field khi class được
 * annotated với {@code @ConfigurationProperties}.
 */
@ConfigurationProperties(prefix = "dht")
public class DhtProperties {

    /** Tên logic của node (dùng để tính nodeId = SHA-1(name) mod 2^M). */
    private String name = "nodeA";

    /** Cổng TCP mà node lắng nghe. */
    private int port = 5001;

    /** Kích thước vòng ring tính bằng bit (M). ID space = [0, 2^M). */
    private int m = 8;

    /** Số lượng successor trong successor list (r) – dùng cho fault tolerance. */
    private int successorListSize = 3;

    /** Khoảng thời gian re-register keys (giây). */
    private int reregisterIntervalSeconds = 30;

    /** Thông tin node bootstrap để join ring. */
    private Bootstrap bootstrap = new Bootstrap();

    // -----------------------------------------------------------------------
    // Getters / Setters (Spring Boot yêu cầu JavaBean convention)
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getM() { return m; }
    public void setM(int m) { this.m = m; }

    public int getSuccessorListSize() { return successorListSize; }
    public void setSuccessorListSize(int successorListSize) { this.successorListSize = successorListSize; }

    public int getReregisterIntervalSeconds() { return reregisterIntervalSeconds; }
    public void setReregisterIntervalSeconds(int reregisterIntervalSeconds) {
        this.reregisterIntervalSeconds = reregisterIntervalSeconds;
    }

    public Bootstrap getBootstrap() { return bootstrap; }
    public void setBootstrap(Bootstrap bootstrap) { this.bootstrap = bootstrap; }

    /** Thông tin kết nối tới node bootstrap khi join ring. */
    public static class Bootstrap {
        private String host = "";
        private int port = 0;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        /** @return true nếu đã cấu hình bootstrap (host không rỗng và port > 0) */
        public boolean isConfigured() {
            return host != null && !host.isBlank() && port > 0;
        }
    }
}
