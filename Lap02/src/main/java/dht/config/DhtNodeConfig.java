package dht.config;

import dht.node.DhtNode;
import dht.routing.HashUtil;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Cấu hình Spring Boot cho DHT node.
 *
 * <p><strong>Trách nhiệm:</strong>
 * <ol>
 *   <li>Đọc cấu hình từ application.yml qua {@link DhtProperties}.</li>
 *   <li>Khởi tạo {@link HashUtil} với đúng giá trị M.</li>
 *   <li>Tạo {@link DhtNode} bean và start nó (join ring nếu có bootstrap).</li>
 *   <li>Kích hoạt {@code @Scheduled} qua {@link EnableScheduling}.</li>
 * </ol>
 *
 * <p>Dùng {@code @EnableConfigurationProperties} để bind DhtProperties từ yaml.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DhtProperties.class)
public class DhtNodeConfig {

    private static final Logger LOG = Logger.getLogger(DhtNodeConfig.class.getName());

    private DhtNode dhtNode;

    /**
     * Tạo và khởi động DhtNode bean.
     *
     * <p>Thứ tự khởi tạo:
     * <ol>
     *   <li>Set M cho HashUtil (phải làm trước khi tạo NodeInfo).</li>
     *   <li>Tạo DhtNode với cấu hình từ properties.</li>
     *   <li>Nếu có bootstrap → join ring; nếu không → start ring mới.</li>
     * </ol>
     *
     * @param props cấu hình đọc từ application.yml (prefix "dht")
     */
    @Bean
    public DhtNode dhtNode(DhtProperties props) throws IOException {
        // Bước 1: Thiết lập kích thước ring
        HashUtil.setM(props.getM());
        LOG.info(String.format("[CONFIG] DHT ring M=%d, kích thước ID space=[0,%d)",
                props.getM(), (int) Math.pow(2, props.getM())));

        // Bước 2: Tạo node
        this.dhtNode = new DhtNode(
                props.getName(),
                "127.0.0.1",
                props.getPort(),
                props.getSuccessorListSize()
        );

        // Bước 3: Join hoặc start
        if (props.getBootstrap().isConfigured()) {
            LOG.info(String.format("[CONFIG] Join ring qua bootstrap %s:%d",
                    props.getBootstrap().getHost(), props.getBootstrap().getPort()));
            dhtNode.join(props.getBootstrap().getHost(), props.getBootstrap().getPort());
        } else {
            LOG.info("[CONFIG] Khởi tạo ring mới (bootstrap node)");
            dhtNode.start();
        }

        return dhtNode;
    }

    /** Dừng node khi Spring context shutdown. */
    @PreDestroy
    public void shutdown() {
        if (dhtNode != null) {
            dhtNode.stop();
        }
    }
}
