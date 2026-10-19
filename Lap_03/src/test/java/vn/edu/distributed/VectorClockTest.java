package vn.edu.distributed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VectorClock}.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>tick() — local/send rule</li>
 *   <li>merge() — receive rule (component-wise max then own increment)</li>
 *   <li>snapshot() — immutability</li>
 *   <li>{@link VectorClock#leq(int[], int[])} — component-wise ≤</li>
 *   <li>{@link VectorClock#happensBefore(int[], int[])} — happens-before (→)</li>
 *   <li>{@link VectorClock#isConcurrent(int[], int[])} — concurrency (∥)</li>
 * </ul>
 */
@DisplayName("VectorClock")
class VectorClockTest {

    // System with 3 processes (indices 0, 1, 2)
    private static final int N = 3;

    // =========================================================================
    // tick()
    // =========================================================================

    @Nested
    @DisplayName("tick()")
    class TickTests {

        @Test
        @DisplayName("P0: tick increments own index only")
        void p0TickIncrementsIndex0() {
            VectorClock vc = new VectorClock(N, 0);
            int[] snap = vc.tick();
            assertArrayEquals(new int[]{1, 0, 0}, snap);
        }

        @Test
        @DisplayName("P1: tick increments own index only")
        void p1TickIncrementsIndex1() {
            VectorClock vc = new VectorClock(N, 1);
            int[] snap = vc.tick();
            assertArrayEquals(new int[]{0, 1, 0}, snap);
        }

        @Test
        @DisplayName("P2: tick increments own index only")
        void p2TickIncrementsIndex2() {
            VectorClock vc = new VectorClock(N, 2);
            int[] snap = vc.tick();
            assertArrayEquals(new int[]{0, 0, 1}, snap);
        }

        @Test
        @DisplayName("Two ticks for P0 → [2, 0, 0]")
        void twoTicksP0() {
            VectorClock vc = new VectorClock(N, 0);
            vc.tick();
            int[] snap = vc.tick();
            assertArrayEquals(new int[]{2, 0, 0}, snap);
        }

        @Test
        @DisplayName("Returned snapshot is a copy – mutating it does not affect clock")
        void snapshotIsCopy() {
            VectorClock vc = new VectorClock(N, 0);
            int[] snap = vc.tick();
            snap[0] = 999;   // mutate the returned array
            // Clock itself must still be [1, 0, 0]
            assertArrayEquals(new int[]{1, 0, 0}, vc.snapshot());
        }
    }

    // =========================================================================
    // merge()
    // =========================================================================

    @Nested
    @DisplayName("merge()")
    class MergeTests {

        @Test
        @DisplayName("Receive: component-wise max then own increment (basic)")
        void mergeBasic() {
            // P1 (index 1) local vector = [0,0,0]
            // receives message with vector [2, 0, 0]
            // expected: max → [2,0,0], then V[1]++ → [2,1,0]
            VectorClock vc = new VectorClock(N, 1);
            int[] result = vc.merge(new int[]{2, 0, 0});
            assertArrayEquals(new int[]{2, 1, 0}, result);
        }

        @Test
        @DisplayName("Receive: local has higher values in some components")
        void mergeTakesMax() {
            // P0 (index 0) has ticked twice: [2,0,0]
            // receives [1, 3, 0] → max → [2,3,0] → V[0]++ → [3,3,0]
            VectorClock vc = new VectorClock(N, 0);
            vc.tick(); vc.tick();  // [2,0,0]
            int[] result = vc.merge(new int[]{1, 3, 0});
            assertArrayEquals(new int[]{3, 3, 0}, result);
        }

        @Test
        @DisplayName("Receive: message vector is all zeros → same as local tick")
        void mergeAllZerosMessage() {
            // P2 has ticked once: [0,0,1]
            // receives [0,0,0] → max → [0,0,1] → V[2]++ → [0,0,2]
            VectorClock vc = new VectorClock(N, 2);
            vc.tick();
            int[] result = vc.merge(new int[]{0, 0, 0});
            assertArrayEquals(new int[]{0, 0, 2}, result);
        }

        @Test
        @DisplayName("Spec scenario: P1 receives [2,0,0] from P0 → [2,1,0]")
        void specScenarioMerge() {
            VectorClock p1 = new VectorClock(N, 1);
            int[] result = p1.merge(new int[]{2, 0, 0});
            assertArrayEquals(new int[]{2, 1, 0}, result);
        }

        @Test
        @DisplayName("merge() argument is not modified by the method")
        void mergeDoesNotMutateInput() {
            VectorClock vc = new VectorClock(N, 0);
            int[] input = {1, 2, 3};
            vc.merge(input);
            assertArrayEquals(new int[]{1, 2, 3}, input);
        }
    }

    // =========================================================================
    // leq()
    // =========================================================================

    @Nested
    @DisplayName("leq(A, B)")
    class LeqTests {

        @Test
        @DisplayName("[0,0,0] <= [0,0,0] → true")
        void zeroLeqZero() {
            assertTrue(VectorClock.leq(new int[]{0,0,0}, new int[]{0,0,0}));
        }

        @Test
        @DisplayName("[1,0,0] <= [2,1,0] → true")
        void p0EventLeqP1AfterRecv() {
            assertTrue(VectorClock.leq(new int[]{1,0,0}, new int[]{2,1,0}));
        }

        @Test
        @DisplayName("[2,1,0] <= [1,0,0] → false")
        void greaterNotLeq() {
            assertFalse(VectorClock.leq(new int[]{2,1,0}, new int[]{1,0,0}));
        }

        @Test
        @DisplayName("[0,0,1] <= [2,1,0] → false (first component: 0<=2 ok, last: 1>0 fail)")
        void concurrentVectorsNotLeq() {
            assertFalse(VectorClock.leq(new int[]{0,0,1}, new int[]{2,1,0}));
        }
    }

    // =========================================================================
    // happensBefore()
    // =========================================================================

    @Nested
    @DisplayName("happensBefore(A, B)")
    class HappensBeforeTests {

        @Test
        @DisplayName("[1,0,0] → [2,1,0] is true (P0's first event happened before P1's recv)")
        void p0EventHappensBeforeP1Recv() {
            assertTrue(VectorClock.happensBefore(
                    new int[]{1, 0, 0},
                    new int[]{2, 1, 0}));
        }

        @Test
        @DisplayName("[0,0,0] → [1,0,0] is true")
        void zeroHappensBeforeFirstTick() {
            assertTrue(VectorClock.happensBefore(
                    new int[]{0, 0, 0},
                    new int[]{1, 0, 0}));
        }

        @Test
        @DisplayName("Equal vectors are NOT happened-before")
        void equalVectorsNotHappensBefore() {
            assertFalse(VectorClock.happensBefore(
                    new int[]{1, 1, 0},
                    new int[]{1, 1, 0}));
        }

        @Test
        @DisplayName("[2,1,0] does NOT happen before [1,0,0]")
        void reversedIsFalse() {
            assertFalse(VectorClock.happensBefore(
                    new int[]{2, 1, 0},
                    new int[]{1, 0, 0}));
        }

        @Test
        @DisplayName("[0,0,1] does NOT happen before [2,1,0] (spec case)")
        void specCaseConcurrentNotHappensBefore() {
            // This is the key spec case: [0,0,1] || [2,1,0]
            // so neither happens before the other
            assertFalse(VectorClock.happensBefore(
                    new int[]{0, 0, 1},
                    new int[]{2, 1, 0}));
        }

        @Test
        @DisplayName("[2,1,0] does NOT happen before [0,0,1] (spec case)")
        void specCaseConcurrentReverseNotHappensBefore() {
            assertFalse(VectorClock.happensBefore(
                    new int[]{2, 1, 0},
                    new int[]{0, 0, 1}));
        }
    }

    // =========================================================================
    // isConcurrent()
    // =========================================================================

    @Nested
    @DisplayName("isConcurrent(A, B)")
    class IsConcurrentTests {

        /**
         * The critical case from the spec:
         * P2 vector [0,0,1] vs P1 vector [2,1,0] MUST be concurrent.
         */
        @Test
        @DisplayName("[0,0,1] || [2,1,0] → true  (spec requirement)")
        void specCaseConcurrent() {
            assertTrue(VectorClock.isConcurrent(
                    new int[]{0, 0, 1},
                    new int[]{2, 1, 0}),
                    "[0,0,1] and [2,1,0] must be concurrent");
        }

        /**
         * Symmetry: isConcurrent(A,B) must equal isConcurrent(B,A).
         */
        @Test
        @DisplayName("[2,1,0] || [0,0,1] → true  (symmetry)")
        void specCaseConcurrentSymmetric() {
            assertTrue(VectorClock.isConcurrent(
                    new int[]{2, 1, 0},
                    new int[]{0, 0, 1}));
        }

        /**
         * Causally related vectors must NOT be concurrent.
         * [1,0,0] → [2,1,0] because the first event of P0 happened before
         * P1 received P0's message.
         */
        @Test
        @DisplayName("[1,0,0] || [2,1,0] → false  (happens-before, not concurrent)")
        void specCaseNotConcurrent() {
            assertFalse(VectorClock.isConcurrent(
                    new int[]{1, 0, 0},
                    new int[]{2, 1, 0}),
                    "[1,0,0] happened before [2,1,0], so they are NOT concurrent");
        }

        @Test
        @DisplayName("Equal vectors are NOT concurrent")
        void equalVectorsNotConcurrent() {
            assertFalse(VectorClock.isConcurrent(
                    new int[]{1, 2, 3},
                    new int[]{1, 2, 3}));
        }

        @Test
        @DisplayName("[1,0,0] || [0,1,0] → true (two independent first events)")
        void independentFirstEvents() {
            assertTrue(VectorClock.isConcurrent(
                    new int[]{1, 0, 0},
                    new int[]{0, 1, 0}));
        }

        @Test
        @DisplayName("[0,0,0] || [0,0,0] → false (identical zeros)")
        void identicalZerosNotConcurrent() {
            assertFalse(VectorClock.isConcurrent(
                    new int[]{0, 0, 0},
                    new int[]{0, 0, 0}));
        }

        @Test
        @DisplayName("One-way dominance: [2,0,0] and [0,1,0] → concurrent")
        void oneWayDominance() {
            assertTrue(VectorClock.isConcurrent(
                    new int[]{2, 0, 0},
                    new int[]{0, 1, 0}));
        }
    }
}
