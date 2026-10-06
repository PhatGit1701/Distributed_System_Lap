# Lap02 — Mini Chord DHT

A fault-tolerant, distributed hash table (DHT) built in Java 21 using the Chord protocol.
Node communication uses raw TCP sockets (`java.net.ServerSocket` / `java.net.Socket`).

---

## Table of Contents

1. [Project Purpose](#project-purpose)
2. [Architecture](#architecture)
3. [How Chord/DHT Works](#how-chorddht-works)
4. [Node ID Generation](#node-id-generation)
5. [Finger Table](#finger-table)
6. [Registry Key Assignment](#registry-key-assignment)
7. [Network Protocol](#network-protocol)
8. [Build](#build)
9. [Run](#run)
10. [Multiple Nodes](#multiple-nodes)
11. [Register a Service](#register-a-service)
12. [Lookup a Service](#lookup-a-service)
13. [Send a Message](#send-a-message)
14. [Simulate an Address Change](#simulate-an-address-change)
15. [Tests](#tests)
16. [O(log N) Lookup Explanation](#olog-n-lookup-explanation)
17. [Known Limitations](#known-limitations)

---

## Project Purpose

Build an educational mini Chord DHT that:

- Assigns every node a **logical identifier** `nodeId = SHA-1(nodeName)` independent of its IP address.
- Routes requests in **O(log N)** hops using a finger table.
- Provides a **distributed registry** mapping logical service names to physical endpoints.
- Tolerates **IP address / port changes** by updating registry entries without changing node identities.
- Communicates exclusively over **TCP sockets** (no HTTP, no RMI).

---

## Architecture

```
src/main/java/
  dht/
    Main.java                    ← CLI entry point
    node/
      DhtNode.java               ← Core node: ring participation, Chord routing
      NodeInfo.java              ← Immutable record: name, nodeId, host, port
      FingerTable.java           ← 160-entry finger table with O(log N) routing
    routing/
      HashUtil.java              ← SHA-1 hashing, ring-interval utilities
    registry/
      Registry.java              ← Local key-value store (per-node)
      RegistryEntry.java         ← Registry entry record
    network/
      Message.java               ← Protocol message (type + payload)
      MessageType.java           ← Enum of all message types
      SocketClient.java          ← Synchronous TCP client helpers
      ConnectionHandler.java     ← Per-connection request handler (Runnable)
    client/
      MessagingClient.java       ← High-level client API

src/test/java/
  dht/
    DhtIntegrationTest.java      ← Integration tests (14 test cases)
    routing/
      HashUtilTest.java          ← Unit tests for hashing & ring math
    node/
      NodeInfoAndFingerTableTest.java
```

### Ring topology (3 nodes)

```
          nodeA
         /     \
        /       \
    nodeC ─── nodeB
```

Chord assigns each node a position on a 2¹⁶⁰ identifier ring. Keys are stored at
their **successor** — the first node clockwise whose `nodeId ≥ keyId`.

---

## How Chord/DHT Works

1. Every node and every key is mapped to a point on a 2^160 ring via SHA-1.
2. Each node maintains:
   - A **successor** (next clockwise node).
   - A **predecessor** (previous clockwise node).
   - A **finger table** with 160 entries pointing to exponentially distant nodes.
3. To look up key `k`:
   - If `k ∈ (self.nodeId, successor.nodeId]` → return successor.
   - Otherwise → forward to `closestPrecedingFinger(k)` and recurse.
4. Background tasks (`stabilize`, `fixNextFinger`) keep the ring consistent as nodes join.

---

## Node ID Generation

```
nodeId = SHA-1(nodeName)
keyId  = SHA-1(serviceName)
```

SHA-1 produces a 160-bit (20-byte) value interpreted as a non-negative `BigInteger`.
The identifier space is `[0, 2^160)`.

**Critical property:** `nodeId` is derived only from the name, not the IP address.
A node can move to a different host/port while keeping the same `nodeId`.

---

## Finger Table

Entry `i` (1-based, i ∈ [1..160]) points to:

```
successor( (nodeId + 2^(i-1)) mod 2^160 )
```

This gives the node references to nodes that are 1, 2, 4, 8, … positions ahead.
During lookup, `closestPrecedingFinger(id)` scans from the largest finger downward
and returns the first finger `f` such that `f.nodeId ∈ (self.nodeId, id)`.

---

## Registry Key Assignment

To register `serviceName`:

```
keyId       = SHA-1(serviceName)
responsible = findSuccessor(keyId)   // Chord routing, O(log N) hops
store entry on responsible
```

To look up `serviceName`, the same routing is used. The entry is always at the
successor of the key's hash — no central server involved.

---

## Network Protocol

All messages are UTF-8 text, one per line (socket).

```
Request format:  TYPE\tPAYLOAD\n
Response format: TYPE\tPAYLOAD\n   or   TYPE\n
```

| Message               | Direction        | Description                          |
|-----------------------|------------------|--------------------------------------|
| `PING`                | client → server  | Liveness check                       |
| `PONG`                | server → client  | Reply to PING                        |
| `GET_INFO`            | client → server  | Request NodeInfo                     |
| `INFO <wire>`         | server → client  | NodeInfo wire string                 |
| `GET_SUCCESSOR <id>`  | client → server  | Find successor for BigInteger id     |
| `SUCCESSOR <wire>`    | server → client  | Successor NodeInfo                   |
| `GET_PREDECESSOR`     | client → server  | Get predecessor NodeInfo             |
| `PREDECESSOR <wire|NONE>` | server → client | Predecessor or NONE               |
| `SET_PREDECESSOR <wire>` | client → server | Update predecessor                  |
| `SET_SUCCESSOR <wire>`   | client → server | Update successor                    |
| `GET_CLOSEST_FINGER <id>` | client → server | Closest preceding finger           |
| `CLOSEST_FINGER <wire>` | server → client | Closest finger NodeInfo             |
| `NOTIFY <wire>`       | client → server  | Stabilize notify                     |
| `REGISTER <n> <h> <p>` | client → server | Register service name               |
| `LOOKUP <name>`       | client → server  | Look up service name                 |
| `LOOKUP_RESULT <wire|NONE>` | server → client | Registry entry or NONE          |
| `SEND <name>\t<msg>`  | client → server  | Route message to named service       |
| `MESSAGE <text>`      | client → server  | Raw message delivery                 |
| `OK [detail]`         | server → client  | Success                              |
| `ERROR <code> <detail>` | server → client | Failure                            |
| `GET_FINGER_TABLE`    | client → server  | Debug: dump finger table             |
| `FINGER_TABLE <data>` | server → client  | Tab-separated NodeInfo wire strings  |

**NodeInfo wire format:** `name|nodeId|host|port`

---

## Build

```powershell
cd Lap02
.\mvnw.cmd package -DskipTests
```

This creates `target/lap02-dht.jar`.

---

## Run

Open a terminal per node. Each node is a separate JVM process.

```powershell
java -jar target/lap02-dht.jar
```

---

## Multiple Nodes

### Terminal 1 — Node A (bootstrap)

```
start nodeA 5001
```

### Terminal 2 — Node B

```
join nodeB 5002 127.0.0.1 5001
```

### Terminal 3 — Node C

```
join nodeC 5003 127.0.0.1 5001
```

Wait ~2 seconds for stabilize to run, then the ring is `A ↔ B ↔ C`.

---

## Register a Service

```
register alice 127.0.0.1 6001
register bob   127.0.0.1 6002
```

The command automatically routes to the responsible DHT node via Chord.

---

## Lookup a Service

```
lookup alice
```

Output:

```
[CLIENT] Looking up 'alice'...
[CLIENT] 'alice' -> 127.0.0.1:6001
[RESULT] alice -> 127.0.0.1:6001
```

---

## Send a Message

```
send alice Hello Alice
```

Output:

```
[CLIENT] Sending to 'alice': Hello Alice
[CLIENT] Result: OK Delivered to 127.0.0.1:6001 -> ...
[RESULT] OK Delivered to 127.0.0.1:6001 -> ...
```

The node receiving the message prints:

```
[MESSAGE] Node 'nodeA' received: Hello Alice
```

---

## Simulate an Address Change

Suppose Alice moves from port 6001 to port 7001.

```
update alice 127.0.0.1 7001
```

Now look up again:

```
lookup alice
```

Returns `127.0.0.1:7001` — the new address.

Then send again:

```
send alice Hello after address change
```

The message is delivered to the new endpoint automatically because the DHT
stores the **logical name** and the **current physical endpoint**, not a permanent address.

---

## Tests

```powershell
.\mvnw.cmd test
```

Test classes:
- `dht.routing.HashUtilTest` — hash determinism, ring intervals, finger start
- `dht.node.NodeInfoAndFingerTableTest` — NodeInfo wire round-trip, finger table init
- `dht.DhtIntegrationTest` — 14 integration tests on localhost (ports 15000–15110)
- `com.example.Lap02.Lap02ApplicationTests` — trivial placeholder

---

## O(log N) Lookup Explanation

In a Chord ring with N nodes:

- Finger table entry `i` covers a distance of `2^(i-1)` on the ring.
- At each hop, the forwarding node uses `closestPrecedingFinger(id)` to jump
  to the farthest-known node that is still closer to the target than itself.
- This at least **halves the remaining ring distance** per hop on average.
- Therefore, lookup requires at most **O(log N)** hops before reaching the successor.

**This implementation uses the finger table for routing.** It does NOT scan all nodes linearly.

---

## Known Limitations

1. **No replication:** Registry entries are stored on exactly one node. If that node crashes, entries are lost.
2. **No data migration on join:** When a new node joins, existing entries on its successor are not moved.
3. **Stabilize delay:** After a join it takes a few stabilize cycles (~2 s each) before the finger table fully converges.
4. **Single-host demo:** All nodes run on `127.0.0.1`. Real deployment needs external IPs.
5. **No TLS:** Communication is plaintext — acceptable for a local educational demo.
6. **No crash recovery:** A crashed node is not automatically removed from finger tables (until the next fix-fingers pass fails and falls back to successor).
7. **Sequential finger fix:** Only one finger entry is updated per fix-fingers tick. Full convergence takes up to `160 × FIX_INTERVAL` seconds on a cold start.
