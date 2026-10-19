package dht.client;

import dht.network.SocketClient;
import dht.node.NodeInfo;

import java.io.IOException;

/**
 * High-level messaging client that interacts with the DHT through a single
 * known entry-point node.
 *
 * <p>The client does NOT maintain a local DHT state — it relies on one DHT
 * node to route requests appropriately.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@link #register(String, String, int)} — register a service name</li>
 *   <li>{@link #lookup(String)} — resolve a service name to NodeInfo</li>
 *   <li>{@link #send(String, String)} — send a message to a named service</li>
 * </ul>
 *
 * <p><strong>IP-change tolerance:</strong> Because the DHT stores logical
 * names and the registry entry is updated when the physical address changes,
 * a subsequent {@link #lookup(String)} will automatically return the new address.
 * The client itself holds no cached addresses.
 */
public final class MessagingClient {

    private final String gatewayHost;
    private final int gatewayPort;

    /**
     * Create a client that sends requests to the given DHT node.
     *
     * @param gatewayHost host of any DHT node in the ring
     * @param gatewayPort port of that node
     */
    public MessagingClient(String gatewayHost, int gatewayPort) {
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
    }

    /**
     * Register a service name in the DHT.
     *
     * <p>The registry entry is routed to the responsible node via Chord.
     *
     * @param name service logical name (e.g. "alice")
     * @param host IP/hostname of the service endpoint
     * @param port TCP port of the service endpoint
     * @throws IOException if the DHT is unreachable or registration fails
     */
    public void register(String name, String host, int port) throws IOException {
        System.out.printf("[CLIENT] Registering '%s' -> %s:%d%n", name, host, port);
        SocketClient.register(gatewayHost, gatewayPort, name, host, port);
        System.out.printf("[CLIENT] Registered '%s' successfully%n", name);
    }

    /**
     * Look up a service name in the DHT.
     *
     * @param name service logical name
     * @return the current NodeInfo for that service, or null if not found
     * @throws IOException if the DHT is unreachable
     */
    public NodeInfo lookup(String name) throws IOException {
        System.out.printf("[CLIENT] Looking up '%s'...%n", name);
        NodeInfo result = SocketClient.lookup(gatewayHost, gatewayPort, name);
        if (result == null) {
            System.out.printf("[CLIENT] '%s' -> NOT FOUND%n", name);
        } else {
            System.out.printf("[CLIENT] '%s' -> %s:%d%n", name, result.host(), result.port());
        }
        return result;
    }

    /**
     * Send a message to the named service.
     *
     * <p>The flow:
     * <ol>
     *   <li>Client sends SEND to the gateway node.</li>
     *   <li>Gateway resolves the name via Chord (O(log N) hops).</li>
     *   <li>Gateway delivers the message to the resolved endpoint.</li>
     *   <li>Result is returned to the client.</li>
     * </ol>
     *
     * @param name    logical service name
     * @param message the text message to deliver
     * @return the response from the target node
     * @throws IOException if the DHT or target is unreachable
     */
    public String send(String name, String message) throws IOException {
        System.out.printf("[CLIENT] Sending to '%s': %s%n", name, message);
        String result = SocketClient.sendMessage(gatewayHost, gatewayPort, name, message);
        System.out.printf("[CLIENT] Result: %s%n", result);
        return result;
    }

    /**
     * Update the registered address of a service (simulate IP/port change).
     *
     * <p>This simply calls {@link #register(String, String, int)} again with
     * the new address — the DHT will overwrite the existing entry.
     *
     * @param name    service logical name
     * @param newHost new IP/hostname
     * @param newPort new TCP port
     * @throws IOException if the DHT is unreachable
     */
    public void updateAddress(String name, String newHost, int newPort) throws IOException {
        System.out.printf("[CLIENT] Updating address for '%s' -> %s:%d%n", name, newHost, newPort);
        register(name, newHost, newPort);
    }
}
