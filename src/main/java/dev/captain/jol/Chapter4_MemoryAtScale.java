package dev.captain.jol;

import org.openjdk.jol.info.GraphLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * CHAPTER 4 — Real Memory Impact at Scale
 *
 * What this shows:
 *   - 4 bytes × 1,000,000 objects = measurable, real heap difference
 *   - GraphLayout measures the total object graph (including the ArrayList itself)
 *   - Raw Runtime heap numbers alongside JOL footprint numbers
 *   - Connection to real Spring Boot / AWS production services
 *
 * How to run:
 *   Java 17/21 (baseline):
 *     mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter4_MemoryAtScale"
 *
 *   Java 25 with compact headers:
 *     java --add-opens java.base/java.lang=ALL-UNNAMED \
 *          -XX:+UseCompactObjectHeaders \
 *          -Xmx512m \
 *          -cp target/jol-explorer-1.0.0-jar-with-dependencies.jar \
 *          dev.captain.jol.Chapter4_MemoryAtScale
 *
 * For the article: run on both JVMs and paste both outputs side by side.
 *
 * NOTE on GraphLayout:
 *   GraphLayout.parseInstance(list) walks the entire object graph reachable from `list`.
 *   This includes: the ArrayList itself, its internal Object[] array, and every Point inside.
 *   toFootprint() gives a summary by class — cleaner for the article than toPrintable().
 *   toPrintable() gives every single object address — useful for debugging, too noisy for article.
 */
public class Chapter4_MemoryAtScale {

    record Point(int x, int y) {}

    // Scale constants — adjust if memory is tight on your machine
    static final int SMALL_SCALE   =       100;
    static final int MEDIUM_SCALE  =    10_000;
    static final int LARGE_SCALE   = 1_000_000;

    public static void main(String[] args) throws InterruptedException {
        printVersionInfo();
        runAtScale(SMALL_SCALE,  "Small  (100 objects)");
        runAtScale(MEDIUM_SCALE, "Medium (10,000 objects)");
        runAtScale(LARGE_SCALE,  "Large  (1,000,000 objects)");
        printProjectedSavings();
        printSpringBootAngle();
    }

    // -------------------------------------------------------------------------
    // Core measurement
    // -------------------------------------------------------------------------

    static void runAtScale(int count, String label) throws InterruptedException {
        banner("SCALE: " + label);

        // Force GC before measuring so we start clean
        System.gc();
        Thread.sleep(100);

        Runtime rt = Runtime.getRuntime();
        long heapBefore = rt.totalMemory() - rt.freeMemory();

        // Build the list
        List<Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new Point(i, i * 2));
        }

        long heapAfter = rt.totalMemory() - rt.freeMemory();
        long heapDelta = heapAfter - heapBefore;

        // JOL graph footprint — walks the entire object graph from 'points'
        System.out.println("--- JOL GraphLayout Footprint ---");
        System.out.println(GraphLayout.parseInstance(points).toFootprint());
        /*
         * Footprint output shows each class in the graph:
         *
         *   COUNT    AVG    SUM    DESCRIPTION
         *       1     XX     XX    java.util.ArrayList
         *       1     XX     XX    [Ljava.lang.Object;   ← the backing array
         *  N_ITEMS     XX     XX    dev.captain.jol.Chapter4_MemoryAtScale$Point
         *
         * SUM for Point class = N_ITEMS × instance_size
         * On Java 17/21: instance_size = 16 bytes (12 header + 4 per int, but two ints fit in 8 bytes after header)
         *   Wait — Point(int,int) = 12 header + 4 (x) + 4 (y) = 20 bytes → padded to 24 bytes
         * On Java 25 CH: instance_size = 8 header + 4 + 4 = 16 bytes — no padding needed!
         *
         * 1,000,000 points:
         *   Java 17/21: 24 MB for Point objects alone
         *   Java 25 CH: 16 MB for Point objects alone
         *   Saving:      8 MB — a 33% reduction just in Point heap usage
         */

        // Raw JVM numbers (less precise than JOL, but intuitive for the article)
        System.out.printf("--- Raw JVM Heap Delta ---%n");
        System.out.printf("  Heap before list:   %,10d bytes  (%,6.1f KB)%n", heapBefore, heapBefore / 1024.0);
        System.out.printf("  Heap after list:    %,10d bytes  (%,6.1f KB)%n", heapAfter, heapAfter / 1024.0);
        System.out.printf("  Delta (approx):     %,10d bytes  (%,6.1f KB)%n", heapDelta, heapDelta / 1024.0);
        System.out.printf("  Objects created:    %,10d%n", count);
        if (count > 0 && heapDelta > 0) {
            System.out.printf("  Approx bytes/obj:   %,10.1f%n", (double) heapDelta / count);
        }
        System.out.println();

        // Explicit GC — don't let the list affect next measurement
        points = null;
        System.gc();
        Thread.sleep(100);
    }

    // -------------------------------------------------------------------------
    // Projected savings table
    // -------------------------------------------------------------------------

    static void printProjectedSavings() {
        banner("PROJECTED SAVINGS — 4 bytes per object across scale");

        System.out.println("""
            These numbers assume a typical mixed heap where Point-like objects
            (small records/DTOs) make up a portion of total objects.
            
            Saving per object (all types): 4 bytes
            Saving per Point specifically:  8 bytes (also removes 4 bytes of forced padding)
            
            OBJECTS       POINT SAVINGS    MIXED HEAP SAVINGS (~4 bytes avg)
            ──────────────────────────────────────────────────────────────
                 1,000         8 KB                      4 KB
                10,000        80 KB                     40 KB
               100,000       800 KB                    400 KB
             1,000,000         8 MB                      4 MB
            10,000,000        80 MB                     40 MB
            
            A Spring Boot service handling 1,000 req/sec, each creating 500 objects:
              → 500,000 new objects per second on the heap
              → Compact headers = 2 MB/sec less allocation pressure
              → GC runs less often → lower p99 latency → cheaper ECS task sizing
            """);
    }

    // -------------------------------------------------------------------------
    // Spring Boot / AWS connection
    // -------------------------------------------------------------------------

    static void printSpringBootAngle() {
        banner("THE PRODUCTION ANGLE — What this means for your Spring Boot service");

        System.out.println("""
            In a typical Spring Boot REST service, every request creates:
              - Request/Response DTOs
              - Entity objects from DB queries
              - Intermediate mapping objects (ModelMapper / MapStruct output)
              - Collection wrappers, Optional, ResponseEntity
            
            All of these are ordinary Java heap objects. All of them pay the header tax.
            
            On Java 25 with -XX:+UseCompactObjectHeaders:
            
            1. HEAP PRESSURE
               Every object is 4 bytes smaller.
               In a GC-heavy service, that's 4 bytes less to allocate and collect.
               At 1M allocations/min, that's ~4 MB/min less allocation rate.
            
            2. GC PAUSE FREQUENCY
               Less live data → eden fills up more slowly → GC triggers less often.
               JEP 519 benchmarks: 15% fewer GC collections on G1 and Parallel GC.
            
            3. ECS / KUBERNETES MEMORY SIZING
               If your ECS task or pod currently uses 2 GB heap, compact headers
               could let you size down to 1.75 GB with the same workload.
               At AWS pricing (~$0.004/vCPU-hr), small heap reductions compound
               across hundreds of tasks running 24/7.
            
            4. AMAZON'S REAL VALIDATION
               Amazon tested compact headers across hundreds of production services
               and successfully backported the feature to JDK 17 and JDK 21.
               They reported measurable improvements across the board.
               This is not a synthetic benchmark story — it's production-validated.
            
            HOW TO ENABLE (Spring Boot on Java 25):
              In your Dockerfile or ECS task definition:
                JAVA_OPTS=-XX:+UseCompactObjectHeaders
              Or in application startup script:
                java -XX:+UseCompactObjectHeaders -jar app.jar
            
            On Java 26: it will be on by default. No flag needed.
            """);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    static void printVersionInfo() {
        banner("JAVA VERSION");
        System.out.println("Java version : " + System.getProperty("java.version"));
        System.out.println("JVM vendor   : " + System.getProperty("java.vm.vendor"));
        System.out.println();
    }

    static void banner(String title) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  " + title);
        System.out.println("=".repeat(70) + "\n");
    }
}
