package vn.edu.distributed;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioTest {
    @Test
    void threadedScenarioLogsClocksAndAutomaticConflicts() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            Main.main(new String[0]);
        } finally {
            System.setOut(original);
        }
        assertEquals("""
                [P0] LOCAL EVENT | Lamport: 1 | Vector: [1, 0, 0]
                [P0] SEND MSG to P1 | Lamport: 2 | Vector: [2, 0, 0]
                [P1] RECV MSG fr P0 | Lamport: 3 | Vector: [2, 1, 0]
                [P2] LOCAL EVENT | Lamport: 1 | Vector: [0, 0, 1]
                [P2] CONFLICT DETECTED with P0! Vector [0,0,1] || [2,0,0]
                [P2] CONFLICT DETECTED with P1! Vector [0,0,1] || [2,1,0]
                """, output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void messageKeepsSendTimestampEvenIfArraysChange() {
        int[] vector = {2, 0, 0};
        Message message = new Message(0, 2, vector);
        vector[0] = 99;
        message.vector()[0] = 88;
        assertArrayEquals(new int[]{2, 0, 0}, message.vector());
    }
}
