package dht;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Điểm vào chính của ứng dụng Spring Boot DHT.
 *
 * <p>{@code @SpringBootApplication} = {@code @Configuration} + {@code @EnableAutoConfiguration}
 * + {@code @ComponentScan} – Spring Boot sẽ tự động quét và wire tất cả beans.
 *
 * <p><strong>Cách chạy:</strong>
 * <pre>
 *   # Build
 *   .\mvnw.cmd package -DskipTests
 *
 *   # Node A (bootstrap)
 *   java -jar target/*.jar --dht.name=nodeA --dht.port=5001
 *
 *   # Node B (join)
 *   java -jar target/*.jar --dht.name=nodeB --dht.port=5002 \
 *        --dht.bootstrap.host=127.0.0.1 --dht.bootstrap.port=5001 \
 *        --server.port=8081
 *
 *   # Xem trạng thái node
 *   curl http://localhost:8080/info
 * </pre>
 */
@SpringBootApplication
public class DhtApplication {

    public static void main(String[] args) {
        SpringApplication.run(DhtApplication.class, args);
    }
}
