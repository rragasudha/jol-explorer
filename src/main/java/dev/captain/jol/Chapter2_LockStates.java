package dev.captain.jol;

import org.openjdk.jol.info.ClassLayout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * CHAPTER 2 — Lock State Changes in the Mark Word
 *
 * What this shows:
 *   - The 8-byte mark word stores completely different data depending on lock state
 *   - On Java 21+: unlocked → thin lock (stack lock) → inflated (fat lock)
 *   - Biased locking is GONE (removed in Java 18 via JEP 374) — you will NOT see it here
 *   - Lock inflation requires real contention — a single thread never inflates
 *
 * IMPORTANT — Java version context:
 *   Java 8–15:  unlocked → biased → thin → inflated
 *   Java 15–17: biased locking deprecated (warning printed)
 *   Java 18+:   biased locking REMOVED — unlocked → thin → inflated only
 *   Java 21+:   same as 18+, plus virtual thread awareness in monitors
 *
 * How to run:
 *   mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter2_LockStates" \
 *     -Dexec.args="" \
 *     -Djvm.args="--add-opens java.base/java.lang=ALL-UNNAMED"
 *
 * JVM arg required: --add-opens java.base/java.lang=ALL-UNNAMED
 */
public class Chapter2_LockStates {

    public static void main(String[] args) throws InterruptedException {
        demo1_SingleThreadLock();
        demo2_ForcedInflation();
        demo3_WaitNotifyInflation();
        printSummary();
    }

    // -------------------------------------------------------------------------
    // Demo 1: Single-threaded lock — shows thin lock
    // -------------------------------------------------------------------------

    /**
     * Single thread acquires and releases a lock.
     * Shows: unlocked → thin locked → unlocked (back to original)
     * Does NOT inflate because there is zero contention.
     */
    static void demo1_SingleThreadLock() throws InterruptedException {
        banner("DEMO 1 — Single-thread lock: unlocked → thin → unlocked");

        Object lock = new Object();

        System.out.println("--- BEFORE synchronization ---");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());
        /*
         * Mark word last bits = 001 → unlocked
         * Header contains: identity hashcode (if computed) + GC age bits
         */

        // Force identity hashcode computation — this is important!
        // Once hashcode is computed, it is stored in the mark word.
        // A thin lock CANNOT co-exist with a stored hashcode in the mark word,
        // so triggering hashcode before locking will cause immediate inflation.
        // We intentionally do NOT call hashCode() here to see thin locking.
        System.out.println("(hashCode not yet called — thin lock is possible)");

        synchronized (lock) {
            System.out.println("\n--- DURING synchronization (single thread, no contention) ---");
            System.out.println(ClassLayout.parseInstance(lock).toPrintable());
            /*
             * Mark word last bits = 00 → thin locked (stack lock)
             * Mark word now contains: pointer to displaced mark word on this thread's stack
             * The original mark word is saved on the stack frame of this synchronized block
             */
        }

        System.out.println("--- AFTER synchronization ---");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());
        /*
         * Mark word restored to unlocked state (001)
         * JVM pops the displaced mark word back from the stack
         * Object looks exactly as it did before the synchronized block
         */
    }

    // -------------------------------------------------------------------------
    // Demo 2: Forced inflation via contention
    // -------------------------------------------------------------------------

    /**
     * Two threads compete for the same lock.
     * Thread 1 holds the lock. Thread 2 tries to acquire it.
     * The JVM must inflate to a fat lock to handle the waiting thread.
     *
     * We use CountDownLatch to coordinate timing precisely so we can print
     * the mark word at the exact moment of contention.
     */
    static void demo2_ForcedInflation() throws InterruptedException {
        banner("DEMO 2 — Two-thread contention: forced lock inflation");

        Object lock = new Object();

        // Latch 1: signals that thread1 has acquired the lock
        CountDownLatch thread1Acquired = new CountDownLatch(1);
        // Latch 2: signals thread1 to release the lock
        CountDownLatch releaseSignal = new CountDownLatch(1);
        // Latch 3: signals that thread2 is waiting for the lock
        CountDownLatch thread2Waiting = new CountDownLatch(1);
        // Latch 4: signals that inflation has been observed
        CountDownLatch inflationObserved = new CountDownLatch(1);

        System.out.println("--- BEFORE any thread acquires lock ---");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());

        // Thread 1: acquires the lock and holds it until told to release
        Thread thread1 = Thread.ofPlatform().name("thread-1").start(() -> {
            synchronized (lock) {
                thread1Acquired.countDown(); // signal: I have the lock
                try {
                    // Wait until the main thread observes inflation
                    inflationObserved.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Wait for thread1 to hold the lock
        thread1Acquired.await(5, TimeUnit.SECONDS);

        System.out.println("--- Thread-1 holds the lock (thin locked by thread-1) ---");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());
        /*
         * At this point: thin lock
         * Mark word bits = 00, contains pointer to thread-1's stack displaced mark word
         */

        // Thread 2: tries to acquire the same lock — this CAUSES inflation
        Thread thread2 = Thread.ofPlatform().name("thread-2").start(() -> {
            thread2Waiting.countDown(); // signal: about to block on lock
            synchronized (lock) {
                // thread2 will get here after thread1 releases
            }
        });

        // Give thread2 time to block on the lock — inflation happens here
        thread2Waiting.await(5, TimeUnit.SECONDS);
        Thread.sleep(200); // small buffer for the JVM to inflate

        System.out.println("--- Thread-2 is BLOCKED waiting — lock should be INFLATED ---");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());
        /*
         * INFLATED state:
         * Mark word last bits = 10 → inflated (fat lock)
         * Mark word now contains: pointer to a Monitor object on the heap
         * The Monitor object has: owner thread, entry list (waiting threads), wait set
         *
         * JVM allocated a Monitor object because it cannot fit two thread states
         * into the 8-byte mark word simultaneously.
         */

        // Signal thread1 to release
        inflationObserved.countDown();

        thread1.join(3000);
        thread2.join(3000);

        System.out.println("--- AFTER both threads release ---");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());
        /*
         * Still shows inflated — the JVM does NOT deflate back to thin lock.
         * Once inflated, stays inflated (Monitor is not immediately freed).
         * Deflation can happen during GC safepoints, but not immediately after release.
         */
    }

    // -------------------------------------------------------------------------
    // Demo 3: wait() forces inflation even without a second thread
    // -------------------------------------------------------------------------

    /**
     * Calling obj.wait() ALSO forces inflation, even with a single thread.
     * This is because wait() requires a Monitor object to manage the wait set.
     * A thin lock has no wait set — it's just a stack pointer.
     */
    static void demo3_WaitNotifyInflation() throws InterruptedException {
        banner("DEMO 3 — wait() forces inflation even without contention");

        Object lock = new Object();

        System.out.println("--- Before wait() ---");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());

        Thread waiter = Thread.ofPlatform().name("waiter").start(() -> {
            synchronized (lock) {
                try {
                    System.out.println("--- Inside synchronized, about to call wait() ---");
                    System.out.println(ClassLayout.parseInstance(lock).toPrintable());

                    lock.wait(500); // immediately inflates to support wait set

                    System.out.println("--- After wait() returns ---");
                    System.out.println(ClassLayout.parseInstance(lock).toPrintable());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        waiter.join(3000);

        System.out.println("--- After thread exits synchronized block ---");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());
        /*
         * You will see inflation happen the moment wait() is called.
         * The Monitor is needed to manage the wait set — no way around it.
         */
    }

    // -------------------------------------------------------------------------
    // Demo 4: hashCode before locking — immediate inflation!
    // -------------------------------------------------------------------------

    // Bonus section — uncomment to include in article output
    /*
    static void demo4_HashCodeInflation() throws InterruptedException {
        banner("BONUS — hashCode() before lock forces inflation");

        Object lock = new Object();

        // Force hashcode computation — stored permanently in mark word
        int hc = System.identityHashCode(lock);
        System.out.println("Identity hashCode: " + hc);

        System.out.println("--- After hashCode computed ---");
        System.out.println(ClassLayout.parseInstance(lock).toPrintable());

        synchronized (lock) {
            System.out.println("--- Inside synchronized (hashCode was pre-computed) ---");
            System.out.println(ClassLayout.parseInstance(lock).toPrintable());
            // INFLATED immediately! Because the thin lock needs to store the displaced
            // mark word on the stack, but the mark word now has a hashcode that must
            // be preserved. The JVM inflates immediately to preserve the hashcode.
        }
    }
    */

    static void printSummary() {
        banner("SUMMARY — Chapter 2");
        System.out.println("""
            What we learned:
            
            1. The mark word is a multipurpose 8-byte field with 3 state bits:
               001 = unlocked         (hashcode + GC age stored here)
               000 = thin locked      (pointer to displaced mark on stack)
               010 = inflated         (pointer to Monitor object on heap)
               011 = marked by GC     (forwarding pointer during collection)
            
            2. Thin lock (stack lock) is the optimistic fast path:
               - JVM copies mark word to thread's stack frame
               - Replaces it with a pointer back to that stack location
               - Zero heap allocation — just a stack write
               - Acquisition: a single CAS (compare-and-swap) instruction
            
            3. Inflation happens when:
               - A second thread tries to acquire a thin-locked object
               - Any thread calls wait() or notify() on the object
               - identity hashCode() was computed BEFORE the first lock
               JVM allocates a Monitor object and rewrites the mark word
            
            4. Biased locking is GONE on Java 18+:
               If you read older articles showing "biased" state — those are
               pre-Java 18. On Java 21, you go straight from unlocked → thin.
            
            5. Once inflated, stays inflated until GC safepoint cleanup.
               The Monitor is not immediately freed after threads release.
            
            Next: Chapter 3 — Java 17 vs Java 25 compact headers, before/after.
            """);
    }

    static void banner(String title) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  " + title);
        System.out.println("=".repeat(70) + "\n");
    }
}
