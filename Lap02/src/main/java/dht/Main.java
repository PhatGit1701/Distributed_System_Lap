package dht;

import dht.client.MessagingClient;
import dht.network.SocketClient;
import dht.node.DhtNode;
import dht.node.NodeInfo;
import dht.routing.HashUtil;

import java.io.IOException;
import java.util.Scanner;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * CLI entry point (dùng độc lập, không qua Spring Boot).
 *
 * <p>Khi chạy bằng Spring Boot ({@link DhtApplication}), class này không được dùng.
 * Dùng class này để chạy thủ công trong terminal (debug/demo).
 *
 * <p><strong>Lệnh hỗ trợ:</strong>
 * <pre>
 *   start  &lt;nodeName&gt; &lt;port&gt;
 *   join   &lt;nodeName&gt; &lt;port&gt; &lt;bootstrapHost&gt; &lt;bootstrapPort&gt;
 *   register &lt;name&gt; &lt;host&gt; &lt;port&gt;
 *   lookup &lt;name&gt;
 *   send   &lt;name&gt; &lt;message&gt;
 *   update &lt;name&gt; &lt;newHost&gt; &lt;newPort&gt;
 *   fingers
 *   info
 *   client &lt;gatewayHost&gt; &lt;gatewayPort&gt;
 *   quit
 * </pre>
 */
public final class Main {

    private static DhtNode localNode = null;
    private static MessagingClient client = null;

    public static void main(String[] args) throws Exception {
        // Khởi tạo M=8 mặc định cho CLI mode
        HashUtil.setM(8);
        configureLogging();

        System.out.println("=================================================");
        System.out.println("  Mini Chord DHT — Distributed Lab 02 (CLI Mode)");
        System.out.println("  Ring size M=8, ID space [0, 255]");
        System.out.println("=================================================");
        System.out.println("Gõ 'help' để xem danh sách lệnh.");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isBlank()) continue;
            try {
                if (!handleCommand(line)) break;
            } catch (Exception e) {
                System.out.println("[LỖI] " + e.getMessage());
            }
        }

        if (localNode != null) localNode.stop();
        System.out.println("Tạm biệt.");
    }

    private static boolean handleCommand(String line) throws IOException {
        String[] tokens = line.split("\\s+", 2);
        String cmd = tokens[0].toLowerCase();
        String rest = tokens.length > 1 ? tokens[1] : "";

        switch (cmd) {
            case "help"     -> printHelp();
            case "start"    -> cmdStart(rest);
            case "join"     -> cmdJoin(rest);
            case "register" -> cmdRegister(rest);
            case "lookup"   -> cmdLookup(rest);
            case "send"     -> cmdSend(rest);
            case "update"   -> cmdUpdate(rest);
            case "fingers"  -> cmdFingers();
            case "info"     -> cmdInfo();
            case "client"   -> cmdClient(rest);
            case "quit", "exit" -> { return false; }
            default -> System.out.println("[LỖI] Lệnh không hợp lệ: " + cmd + ". Gõ 'help'.");
        }
        return true;
    }

    private static void cmdStart(String rest) throws IOException {
        String[] parts = rest.split("\\s+");
        if (parts.length < 2) { usage("start <nodeName> <port>"); return; }
        String name = parts[0];
        int port = Integer.parseInt(parts[1]);

        if (localNode != null) localNode.stop();
        localNode = new DhtNode(name, "127.0.0.1", port);
        localNode.start();
        client = new MessagingClient("127.0.0.1", port);
        System.out.printf("[OK] Đã khởi động node '%s' tại 127.0.0.1:%d (id=%s)%n",
                name, port, HashUtil.hash(name));
    }

    private static void cmdJoin(String rest) throws IOException {
        String[] parts = rest.split("\\s+");
        if (parts.length < 4) { usage("join <nodeName> <port> <bootstrapHost> <bootstrapPort>"); return; }
        String name = parts[0];
        int port = Integer.parseInt(parts[1]);
        String bHost = parts[2];
        int bPort = Integer.parseInt(parts[3]);

        if (localNode != null) localNode.stop();
        localNode = new DhtNode(name, "127.0.0.1", port);
        localNode.join(bHost, bPort);
        client = new MessagingClient("127.0.0.1", port);
        System.out.printf("[OK] Node '%s' đã join ring qua %s:%d%n", name, bHost, bPort);
    }

    private static void cmdRegister(String rest) throws IOException {
        String[] parts = rest.split("\\s+");
        if (parts.length < 3) { usage("register <name> <host> <port>"); return; }
        String name = parts[0];
        String host = parts[1];
        int port = Integer.parseInt(parts[2]);

        if (client != null) {
            client.register(name, host, port);
        } else if (localNode != null) {
            localNode.handleRegister(name, host, port);
            System.out.println("[OK] Đã đăng ký " + name);
        } else {
            System.out.println("[LỖI] Chưa có node hoặc client. Dùng 'start', 'join', hoặc 'client'.");
        }
    }

    private static void cmdLookup(String rest) throws IOException {
        String name = rest.trim();
        if (name.isBlank()) { usage("lookup <name>"); return; }

        if (client != null) {
            NodeInfo result = client.lookup(name);
            System.out.printf("[KẾT QUẢ] %s -> %s%n", name,
                    result == null ? "KHÔNG TÌM THẤY" : result.host() + ":" + result.port());
        } else if (localNode != null) {
            NodeInfo result = localNode.handleLookup(name);
            System.out.printf("[KẾT QUẢ] %s -> %s%n", name,
                    result == null ? "KHÔNG TÌM THẤY" : result.host() + ":" + result.port());
        } else {
            System.out.println("[LỖI] Chưa có node hoặc client.");
        }
    }

    private static void cmdSend(String rest) throws IOException {
        int space = rest.indexOf(' ');
        if (space < 0) { usage("send <name> <message>"); return; }
        String name = rest.substring(0, space);
        String message = rest.substring(space + 1);

        if (client != null) {
            System.out.println("[KẾT QUẢ] " + client.send(name, message));
        } else if (localNode != null) {
            MessagingClient selfClient = new MessagingClient(
                    localNode.getSelf().host(), localNode.getSelf().port());
            System.out.println("[KẾT QUẢ] " + selfClient.send(name, message));
        } else {
            System.out.println("[LỖI] Chưa có node hoặc client.");
        }
    }

    private static void cmdUpdate(String rest) throws IOException {
        String[] parts = rest.split("\\s+");
        if (parts.length < 3) { usage("update <name> <newHost> <newPort>"); return; }
        String name = parts[0];
        String newHost = parts[1];
        int newPort = Integer.parseInt(parts[2]);

        if (client != null) {
            client.updateAddress(name, newHost, newPort);
        } else if (localNode != null) {
            localNode.handleRegister(name, newHost, newPort);
            System.out.printf("[OK] Đã cập nhật '%s' -> %s:%d%n", name, newHost, newPort);
        } else {
            System.out.println("[LỖI] Chưa có node hoặc client.");
        }
    }

    private static void cmdFingers() {
        if (localNode != null) {
            System.out.println("[FINGER TABLE] " + localNode.getSelf().name()
                    + " (id=" + localNode.getSelf().nodeId() + ")");
            System.out.print(localNode.getFingerTable().prettyPrint());
        } else {
            System.out.println("[LỖI] Chưa có node.");
        }
    }

    private static void cmdInfo() {
        if (localNode != null) {
            System.out.println("[INFO] " + localNode);
            System.out.printf("  Self      : %s%n", localNode.getSelf());
            System.out.printf("  Successor : %s%n", localNode.getSuccessor());
            System.out.printf("  Predecessor: %s%n", localNode.getPredecessor());
            System.out.printf("  SuccList  : %s%n", localNode.getSuccessorList());
        } else {
            System.out.println("[LỖI] Chưa có node.");
        }
    }

    private static void cmdClient(String rest) {
        String[] parts = rest.split("\\s+");
        if (parts.length < 2) { usage("client <gatewayHost> <gatewayPort>"); return; }
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        client = new MessagingClient(host, port);
        System.out.printf("[OK] Client đã kết nối tới gateway %s:%d%n", host, port);
    }

    private static void usage(String format) {
        System.out.println("[CÁCH DÙNG] " + format);
    }

    private static void printHelp() {
        System.out.println("""
                Lệnh hợp lệ:
                  start  <nodeName> <port>
                  join   <nodeName> <port> <bootstrapHost> <bootstrapPort>
                  register <name> <host> <port>
                  lookup <name>
                  send   <name> <message>
                  update <name> <newHost> <newPort>
                  fingers
                  info
                  client <gatewayHost> <gatewayPort>
                  quit
                """);
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (var h : root.getHandlers()) root.removeHandler(h);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.INFO);
        ch.setFormatter(new SimpleFormatter() {
            @Override public String format(java.util.logging.LogRecord r) {
                return r.getMessage() + "%n".formatted();
            }
        });
        root.addHandler(ch);
    }
}
