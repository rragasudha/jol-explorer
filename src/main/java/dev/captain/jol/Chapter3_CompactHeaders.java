package dev.captain.jol;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

/**
 * CHAPTER 3 — Java 25 Compact Object Headers (JEP 519) vs Java 17/21
 *
 * What this shows:
 *   - On Java 17/21: header = 12 bytes (8 mark word + 4 class pointer)
 *   - On Java 25 with -XX:+UseCompactObjectHeaders: header = 8 bytes (merged)
 *   - Same POJO = smaller instance size, less heap, fewer GC cycles
 *
 * IMPORTANT — How to run on each Java version:
 *
 *   Java 17 or 21 (default, no flags needed):
 *     mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter3_CompactHeaders"
 *     → Shows 12-byte header baseline
 *
 *   Java 24 (experimental, requires unlock flag):
 *     java --add-opens java.base/java.lang=ALL-UNNAMED \
 *          -XX:+UnlockExperimentalVMOptions \
 *          -XX:+UseCompactObjectHeaders \
 *          -cp target/jol-explorer-1.0.0-jar-with-dependencies.jar \
 *          dev.captain.jol.Chapter3_CompactHeaders
 *
 *   Java 25 (production opt-in, no unlock needed):
 *     java --add-opens java.base/java.lang=ALL-UNNAMED \
 *          -XX:+UseCompactObjectHeaders \
 *          -cp target/jol-explorer-1.0.0-jar-with-dependencies.jar \
 *          dev.captain.jol.Chapter3_CompactHeaders
 *
 *   Java 26 (on by default — no flags needed):
 *     mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter3_CompactHeaders"
 *     → Will show 8-byte header automatically
 *
 * Docker approach (to run both versions and compare):
 *   See CLAUDE.md for Docker commands to run Java 17 and Java 25 containers.
 *
 * JEP history:
 *   JEP 450 — Java 24 — experimental
 *   JEP 519 — Java 25 — production (opt-in with -XX:+UseCompactObjectHeaders)
 *   JEP 534 — Java 26 — on by default
 */
public class Chapter3_CompactHeaders {

    // -------------------------------------------------------------------------
    // The same POJOs we'll inspect on both Java versions
    // -------------------------------------------------------------------------

    /** Mixed fields — same as Chapter 1. We'll compare sizes across JVM versions. */
    static class MixedFieldPojo {
        boolean flag;
        int count;
        long value;
        String name;
    }

    /** Minimal: two ints — the simplest non-trivial POJO. */
    static class Point {
    int x;
    int y;
    Point(int x, int y) { this.x = x; this.y = y; }
}

    /** Heavier object — more fields means the 4-byte saving is still fixed. */
    static class HeavyPojo {
        long a, b, c;
        int d, e;
        String f, g;
        boolean h;
    }

    /** An empty object — shows the raw header cost with zero data. */
    static class EmptyObject {}

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        printVersionAndHeaderInfo();
        inspectEmptyObject();
        inspectPoint();
        inspectMixedFieldPojo();
        inspectHeavyPojo();
        printComparisonTable();
        printCompactHeaderExplanation();
    }

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    static void printVersionAndHeaderInfo() {
        banner("JAVA VERSION & JVM DETAILS");
        System.out.println("Java version : " + System.getProperty("java.version"));
        System.out.println("JVM vendor   : " + System.getProperty("java.vm.vendor"));
        System.out.println("JVM name     : " + System.getProperty("java.vm.name"));
        System.out.println();
        System.out.println(VM.current().details());
        /*
         * The line to look for:
         *   "header size: 12 bytes"  → standard layout (Java 17/21)
         *   "header size: 8 bytes"   → compact headers active (Java 25+)
         */
    }

    static void inspectEmptyObject() {
        banner("EMPTY OBJECT — pure header cost, zero data");
        System.out.println(ClassLayout.parseInstance(new EmptyObject()).toPrintable());
        /*
         * Java 17/21:  Instance size: 16 bytes  (12 header + 4 padding)
         * Java 25 CH:  Instance size: 8 bytes   ( 8 header + 0 padding — perfectly aligned!)
         *
         * The empty object is the clearest demonstration: the entire size IS the header.
         * 16 → 8 is a 50% reduction.
         */
    }

    static void inspectPoint() {
        banner("RECORD Point(int x, int y) — 8 bytes of real data");
        System.out.println(ClassLayout.parseInstance(new Point(3, 7)).toPrintable());
        /*
         * Java 17/21:
         *   OFFSET  SIZE   TYPE
         *        0     4        (object header - mark)
         *        4     4        (object header - mark)
         *        8     4        (object header - class)
         *       12     4    int Point.x
         *       16     4    int Point.y
         *       20     4        (alignment/padding gap)
         *   Instance size: 24 bytes
         *
         * Java 25 (compact):
         *   OFFSET  SIZE   TYPE
         *        0     4        (object header - compact)
         *        4     4        (object header - compact)
         *        8     4    int Point.x
         *       12     4    int Point.y
         *   Instance size: 16 bytes
         *
         * Savings: 8 bytes per Point.
         * At 1M points: 8 MB saved. AWS cost: ~$0.25/month per GB in ECS memory.
         * At 10M points: 80 MB saved — measurable GC pressure reduction.
         */
    }

    static void inspectMixedFieldPojo() {
        banner("MixedFieldPojo — boolean + int + long + String");
        MixedFieldPojo obj = new MixedFieldPojo();
        obj.flag = true; obj.count = 42; obj.value = 1_000_000L; obj.name = "captain";
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        /*
         * Java 17/21:  Instance size: 32 bytes
         * Java 25 CH:  Instance size: 28 bytes
         * Saving: 4 bytes per instance
         *
         * Note: the savings is always exactly 4 bytes per object (the saved class pointer).
         * Regardless of how many fields the object has.
         */
    }

    static void inspectHeavyPojo() {
        banner("HeavyPojo — 3 longs + 2 ints + 2 Strings + 1 boolean");
        System.out.println(ClassLayout.parseInstance(new HeavyPojo()).toPrintable());
        /*
         * Even a big object only saves the same 4 bytes.
         * The saving per object is fixed — it's just the class pointer bytes.
         * Impact is more visible with millions of objects, not with object size.
         */
    }

    static void printComparisonTable() {
        banner("COMPARISON TABLE — Expected sizes (run this on both JVMs to fill in)");

        System.out.println("""
            CLASS               | Java 17/21 | Java 25 (+compact) | SAVED
            --------------------|------------|---------------------|------
            EmptyObject         |   16 bytes |            8 bytes  |    8
            Point(int,int)      |   24 bytes |           16 bytes  |    8
            MixedFieldPojo      |   32 bytes |           28 bytes  |    4
            HeavyPojo           |   56 bytes |           52 bytes  |    4
            int[] (length=10)   |   56 bytes |           52 bytes  |    4
            
            Note: Empty object saves 8 bytes because removing 4 bytes of header
            also removes 4 bytes of padding that was forced by the 12-byte header.
            All other objects save exactly 4 bytes (the class pointer).
            
            Run this class on Java 17 and Java 25 and paste the actual output
            from ClassLayout.parseInstance().toPrintable() into the article.
            """);
    }

    static void printCompactHeaderExplanation() {
        banner("HOW COMPACT HEADERS WORK — The Engineering Story");
        System.out.println("""
            BEFORE (Java 8–21): 12 bytes
            ┌─────────────────────────────────────────────────────┐
            │  Mark Word (8 bytes)  │  Class Pointer (4 bytes)    │
            │  hashcode | lock bits │  compressed klass pointer   │
            └─────────────────────────────────────────────────────┘
            
            The mark word and class pointer are stored separately.
            Mark word = 64 bits of lock/hashcode/GC data.
            Class pointer = 32 bits pointing to class metadata in metaspace.
            
            AFTER (Java 25 with -XX:+UseCompactObjectHeaders): 8 bytes
            ┌─────────────────────────────────────────────────────┐
            │              Compact Header (8 bytes)               │
            │  [class ptr: 22 bits][hash: 25 bits][lock: 4 bits]  │
            │  [valhalla: 4 bits][GC age: 4 bits][misc: 5 bits]   │
            └─────────────────────────────────────────────────────┘
            
            Key changes:
            
            1. Class pointer compressed from 32 bits → 22 bits
               Tradeoff: max ~4 million distinct classes per JVM (fine for any real app)
               Benefit: frees up 10 bits that can be used for other purposes
            
            2. Mark word bits reshuffled into remaining space
               Identity hashcode: 25 bits (still supports ~33M unique hashcodes)
               Lock state: 4 bits
               GC age: 4 bits
               Reserved (Project Valhalla): 4 bits
               
            3. The 4 Valhalla bits:
               Project Valhalla is building "value types" — objects with no identity,
               no locking, stored inline like primitives. These 4 bits will let the JVM
               distinguish value objects from identity objects at the header level.
               The bits are reserved now, used later.
            
            Why now and not in 2012?
               The prerequisite was removing biased locking (Java 18) — biased locking
               stored thread IDs in the mark word and needed more space. Once biased
               locking was gone, the bit budget opened up enough for this to work.
               Amazon successfully backported the implementation to JDK 17 and JDK 21.
            
            Benchmark results (from JEP 519 — SPECjbb2015):
               - 22% less heap usage
               - 15% fewer GC collections
               - 8% less CPU time
               - 10% faster on parallel JSON parsing workload
            """);
    }

    static void banner(String title) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  " + title);
        System.out.println("=".repeat(70) + "\n");
    }
}
