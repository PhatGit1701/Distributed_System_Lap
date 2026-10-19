package dht.web;

import dht.node.DhtNode;
import dht.node.NodeInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller cung cấp endpoint debugging cho DHT node.
 *
 * <p><strong>Endpoints:</strong>
 * <ul>
 *   <li>{@code GET /info} – Trả về thông tin node: self, successor, predecessor,
 *       successor list, finger table.</li>
 * </ul>
 *
 * <p>Dùng để debug trạng thái ring mà không cần kết nối TCP thủ công.
 * Ví dụ: {@code curl http://localhost:8080/info | python -m json.tool}
 */
@RestController
public class InfoController {

    private final DhtNode node;

    public InfoController(DhtNode node) {
        this.node = node;
    }

    /**
     * GET /info – Trả về trạng thái hiện tại của DHT node dưới dạng JSON.
     *
     * <p>Response bao gồm:
     * <ul>
     *   <li>{@code self} – NodeInfo của node này</li>
     *   <li>{@code successor} – Successor hiện tại</li>
     *   <li>{@code predecessor} – Predecessor hiện tại (null nếu chưa có)</li>
     *   <li>{@code successorList} – Danh sách r successors (fault tolerance)</li>
     *   <li>{@code fingerTable} – Toàn bộ finger table</li>
     * </ul>
     *
     * @return Map được Spring tự chuyển thành JSON
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Thông tin node này
        result.put("self", nodeInfoToMap(node.getSelf()));

        // Successor và predecessor
        result.put("successor", nodeInfoToMap(node.getSuccessor()));
        result.put("predecessor", nodeInfoToMap(node.getPredecessor()));

        // Successor list (fault tolerance)
        List<Map<String, Object>> succList = new ArrayList<>();
        for (NodeInfo n : node.getSuccessorList()) {
            succList.add(nodeInfoToMap(n));
        }
        result.put("successorList", succList);

        // Finger table
        List<Map<String, Object>> fingers = new ArrayList<>();
        for (int i = 1; i <= dht.node.FingerTable.SIZE; i++) {
            NodeInfo f = node.getFingerTable().get(i);
            if (f != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", i);
                entry.put("node", nodeInfoToMap(f));
                fingers.add(entry);
            }
        }
        result.put("fingerTable", fingers);

        return result;
    }

    /** Chuyển NodeInfo thành Map để JSON serialization. */
    private Map<String, Object> nodeInfoToMap(NodeInfo info) {
        if (info == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", info.name());
        map.put("nodeId", info.nodeId().toString());
        map.put("host", info.host());
        map.put("port", info.port());
        return map;
    }
}
