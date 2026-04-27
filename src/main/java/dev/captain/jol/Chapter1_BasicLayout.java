package dev.captain.jol;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

/**
 * CHAPTER 1 — Basic Object Layout and Field Reordering
 *
 * What this shows:
 *   - Every object starts with a 12-byte header (on Java 17/21)
 *   - JVM reorders fields by size to minimize padding — ignoring your declaration order
 *   - Padding bytes are added at the end to align total size to a multiple of 8
 *
 * How to run:
 *   mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter1_BasicLayout" \
 *     -Dexec.args="" \
 *     -Djvm.args="--add-opens java.base/java.lang=ALL-UNNAMED"
 *
 * Or from an IDE: run main() directly.
 * JVM arg required: --add-opens java.base/java.lang=ALL-UNNAMED
 */
public class Chapter1_BasicLayout {

    // -------------------------------------------------------------------------
    // Model classes — declared in "bad" order to show JVM reordering
    // -------------------------------------------------------------------------

    /**
     * Fields declared smallest-first in source code.
     * JVM will reorder them largest-first in memory to avoid padding waste.
     *
     * Declaration order: boolean(1) → int(4) → long(8) → String(ref,4)
     * Expected JVM order: long(8) → int(4) → String(ref,4) → boolean(1) → [3 pad]
     */
    static class MixedFieldPojo {
        boolean flag;    // 1 byte
        int count;       // 4 bytes
        long value;      // 8 bytes  ← JVM will move this first
        String name;     // 4 bytes (compressed reference)
    }

    /**
     * A deliberately wasteful layout — if the JVM did NOT reorder.
     * Declared largest-first; this should match what the JVM actually does.
     */
    static class WellOrderedPojo {
        long value;      // 8 bytes
        int count;       // 4 bytes
        String name;     // 4 bytes (compressed reference)
        boolean flag;    // 1 byte → 3 bytes padding after this
    }

    /**
     * Empty class — shows the absolute minimum object cost.
     * Header only, no fields, but still takes 16 bytes due to alignment.
     */
    static class EmptyObject {}

    /**
     * Single int field — shows how even one tiny field still pays the 12-byte header tax.
     */
    static class SingleInt {
        int value;
    }

    /**
     * A record — syntactic sugar, but JVM treats it as a normal class.
     * Worth showing: records don't get any special compact memory treatment.
     */
    record Point(int x, int y) {}

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        System.setProperty("jol.magicFieldOffset", "true");
        printVmInfo();
        showEmptyObject();
        showSingleInt();
        showMixedFieldReordering();
        showWellOrdered();
        showRecord();
        printSummary();
    }

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    static void printVmInfo() {
        banner("JVM DETAILS");
        System.out.println(VM.current().details());
        /*
         * What to look for:
         *   - "object alignment"  → 8 bytes (all objects padded to multiple of 8)
         *   - "field sizes"       → bool=1, int=4, long=8, ref=4 (compressed oops)
         *   - "header size"       → 12 bytes on Java 17/21
         *                           8 bytes  on Java 25 with -XX:+UseCompactObjectHeaders
         */
    }

    static void showEmptyObject() {
        banner("EMPTY OBJECT");
        EmptyObject obj = new EmptyObject();
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        /*
         * Expected output (Java 17/21):
         *
         * dev.captain.jol.Chapter1_BasicLayout$EmptyObject object internals:
         * OFFSET  SIZE   TYPE DESCRIPTION                    VALUE
         *      0     4        (object header - mark)         0x0000...
         *      4     4        (object header - mark)         0x0000...
         *      8     4        (object header - class)        0x...
         *     12     4        (alignment/padding gap)
         * Instance size: 16 bytes
         *
         * Key insight: Even an empty object costs 16 bytes.
         *   - 12 bytes header
         *   - 4 bytes padding to reach next multiple of 8 → lands at 16
         */
    }

    static void showSingleInt() {
        banner("SINGLE INT FIELD");
        SingleInt obj = new SingleInt();
        obj.value = 42;
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        /*
         * Expected output (Java 17/21):
         *
         * OFFSET  SIZE   TYPE DESCRIPTION     VALUE
         *      0     4        (object header - mark)
         *      4     4        (object header - mark)
         *      8     4        (object header - class)
         *     12     4    int SingleInt.value   42
         * Instance size: 16 bytes
         *
         * Key insight: One int field fits perfectly into the 4 bytes after the
         * 12-byte header, bringing total to 16 (a clean multiple of 8). No padding needed.
         */
    }

    static void showMixedFieldReordering() {
        banner("MIXED FIELDS — declared boolean→int→long→String, watch JVM reorder");
        MixedFieldPojo obj = new MixedFieldPojo();
        obj.flag = true;
        obj.count = 100;
        obj.value = 999_999L;
        obj.name = "hello";
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        /*
         * Expected output (Java 17/21):
         *
         * OFFSET  SIZE      TYPE DESCRIPTION         VALUE
         *      0     4           (object header - mark)
         *      4     4           (object header - mark)
         *      8     4           (object header - class)
         *     12     4       int MixedFieldPojo.count     100      ← int moved up
         *     16     8      long MixedFieldPojo.value     999999   ← long moved up
         *     24     4    String MixedFieldPojo.name      (ref)
         *     28     1   boolean MixedFieldPojo.flag      true     ← boolean last
         *     29     3           (loss due to the next object alignment)
         * Instance size: 32 bytes
         *
         * Declaration order:  boolean(1) int(4) long(8) String(4)  = 17 bytes of data
         * Memory order:       int(4) long(8) String(4) boolean(1)  = same data, less padding
         *
         * The JVM uses a specific ordering algorithm:
         *   1. doubles / longs (8 bytes)
         *   2. ints / floats   (4 bytes)
         *   3. shorts / chars  (2 bytes)
         *   4. bytes / booleans(1 byte)
         *   5. references      (4 bytes with compressed oops)
         * Fields of the same size stay in declaration order relative to each other.
         */
    }

    static void showWellOrdered() {
        banner("WELL-ORDERED FIELDS — same data, declared in JVM-preferred order");
        WellOrderedPojo obj = new WellOrderedPojo();
        obj.flag = true;
        obj.count = 100;
        obj.value = 999_999L;
        obj.name = "hello";
        System.out.println(ClassLayout.parseInstance(obj).toPrintable());
        /*
         * Layout should be nearly identical to MixedFieldPojo above.
         * This confirms the JVM normalizes regardless of your declaration order.
         * You cannot manually optimize field layout in Java (unlike C/C++ structs).
         */
    }

    static void showRecord() {
        banner("RECORD — Point(int x, int y)");
        Point p = new Point(3, 7);
        System.out.println(ClassLayout.parseInstance(p).toPrintable());
        /*
         * Expected output (Java 17/21):
         *
         * OFFSET  SIZE   TYPE DESCRIPTION   VALUE
         *      0     4        (object header - mark)
         *      4     4        (object header - mark)
         *      8     4        (object header - class)
         *     12     4    int Point.x         3
         *     16     4    int Point.y         7
         *     20     4        (alignment/padding gap)
         * Instance size: 24 bytes
         *
         * Key insight: Records are normal JVM objects. No compact storage, no primitive
         * inlining. That's what Project Valhalla (value types) will eventually fix.
         * A Point(int,int) record costs 24 bytes — 16 bytes more than the 8 bytes of
         * data it actually holds.
         */
    }

    static void printSummary() {
        banner("SUMMARY — Chapter 1");
        System.out.println("""
            What we learned:
            
            1. Every object pays a 12-byte header tax (on Java 17/21).
               Even an empty object costs 16 bytes after alignment padding.
            
            2. JVM reorders fields by descending size:
               long/double (8) → int/float (4) → short/char (2) → byte/boolean (1) → refs (4)
               You cannot override this in Java. It's a JVM decision, not yours.
            
            3. Total object size is always a multiple of 8 bytes (object alignment).
               Padding bytes are inserted at the end to enforce this.
            
            4. A Point(int,int) record = 24 bytes. Actual data = 8 bytes.
               Header + alignment wastes 16 bytes — 2x the data it stores.
               This is the "header tax" that JEP 519 starts to address.
            
            Next: Chapter 2 — watch the mark word change as a lock is acquired.
            """);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    static void banner(String title) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  " + title);
        System.out.println("=".repeat(70) + "\n");
    }
}
