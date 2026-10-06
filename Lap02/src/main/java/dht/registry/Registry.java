package dht.registry;

import dht.node.NodeInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local key-value store for registry entries held by this DHT node.
 *
 * <p>This is the <em>local</em> portion of the distributed registry — each
 * node only stores entries whose key hash falls in its responsibility range.
 * Together, all nodes' {@code Registry} instances constitute the global registry.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}.
 */
public final class Registry {

    /** Map from logical service name (lowercase) to its current entry. */
    private final Map<String, RegistryEntry> store = new ConcurrentHashMap<>();

    /**
     * Store or update a registry entry.
     *
     * @param name     logical service name
     * @param nodeInfo NodeInfo of the service endpoint
     */
    public void put(String name, NodeInfo nodeInfo) {
        store.put(name.toLowerCase(), new RegistryEntry(name, nodeInfo));
    }

    /**
     * Retrieve the registry entry for a service name.
     *
     * @param name logical service name (case-insensitive)
     * @return the entry, or {@code null} if not found
     */
    public RegistryEntry get(String name) {
        return store.get(name.toLowerCase());
    }

    /**
     * Remove an entry.
     *
     * @param name logical service name
     */
    public void remove(String name) {
        store.remove(name.toLowerCase());
    }

    /** @return number of entries currently stored on this node */
    public int size() {
        return store.size();
    }

    /** @return a snapshot of all stored entries */
    public Map<String, RegistryEntry> snapshot() {
        return Map.copyOf(store);
    }
}
