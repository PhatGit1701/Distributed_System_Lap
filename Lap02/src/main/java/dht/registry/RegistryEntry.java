package dht.registry;

import dht.node.NodeInfo;

/**
 * An entry in the distributed registry mapping a logical service name to
 * a {@link NodeInfo} that currently hosts that service.
 *
 * <p>The key stored here is the <em>logical name</em> (e.g. "alice"),
 * not the SHA-1 hash. The hash is used only for routing decisions.
 */
public record RegistryEntry(String name, NodeInfo nodeInfo) {

    /** Wire format: {@code name\tnodeInfoWire}. */
    public String toWireString() {
        return name + "\t" + nodeInfo.toWireString();
    }

    /** Parse from {@link #toWireString()}. */
    public static RegistryEntry fromWireString(String s) {
        int tab = s.indexOf('\t');
        if (tab < 0) throw new IllegalArgumentException("Invalid RegistryEntry wire: " + s);
        String name = s.substring(0, tab);
        NodeInfo ni = NodeInfo.fromWireString(s.substring(tab + 1));
        return new RegistryEntry(name, ni);
    }
}
