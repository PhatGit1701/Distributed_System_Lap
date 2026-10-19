package dht.network;

/**
 * Represents a single DHT protocol message.
 *
 * <p>A message consists of a {@link MessageType} and an optional payload
 * string (may be null for type-only messages like PING).
 *
 * <p>Wire format over a text stream (one message per line):
 * <pre>
 *   TYPE\tPAYLOAD\n
 * </pre>
 * or, when payload is absent:
 * <pre>
 *   TYPE\n
 * </pre>
 *
 * <p>The TAB character is used as a delimiter between type and payload because
 * payload may contain spaces (e.g. message text).
 */
public record Message(MessageType type, String payload) {

    /** Convenience factory for messages without a payload. */
    public static Message of(MessageType type) {
        return new Message(type, null);
    }

    /** Convenience factory for messages with a payload. */
    public static Message of(MessageType type, String payload) {
        return new Message(type, payload);
    }

    /**
     * Serialise this message to the wire format: {@code TYPE\tPAYLOAD} or {@code TYPE}.
     */
    public String toWire() {
        if (payload == null || payload.isEmpty()) {
            return type.name();
        }
        return type.name() + "\t" + payload;
    }

    /**
     * Parse one line from the wire into a {@code Message}.
     *
     * @param line a single text line received over the socket
     * @return the parsed Message
     * @throws IllegalArgumentException if the line cannot be parsed
     */
    public static Message parse(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("Empty message line");
        }
        int tab = line.indexOf('\t');
        if (tab < 0) {
            MessageType type = MessageType.valueOf(line.trim().toUpperCase());
            return new Message(type, null);
        }
        MessageType type = MessageType.valueOf(line.substring(0, tab).trim().toUpperCase());
        String payload = line.substring(tab + 1);
        return new Message(type, payload);
    }

    @Override
    public String toString() {
        return toWire();
    }
}
