package dev.captain.jol;

import org.openjdk.jol.info.ClassLayout;

/**
 * CHAPTER 5 — Array Layout and Inheritance Layout
 *
 * What this shows:
 *   - Arrays have an extra 4-byte `length` field in their header (array header = 16 bytes)
 *   - Parent class fields always come before subclass fields in memory
 *   - JVM never interleaves parent and child fields, even when that would save padding
 *   - A subclass cannot "fill" a padding gap left by the parent class
 *
 * How to run:
 *   mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter5_ArraysAndInheritance"
 *   JVM arg required: --add-opens java.base/java.lang=ALL-UNNAMED
 */
public class Chapter5_ArraysAndInheritance {

    // -------------------------------------------------------------------------
    // Array models
    // -------------------------------------------------------------------------

    // Arrays are not user-defined classes — we use built-in types directly:
    //   int[], long[], boolean[], Object[], String[]

    // -------------------------------------------------------------------------
    // Inheritance models
    // -------------------------------------------------------------------------

    /**
     * Parent class with a long field — deliberately chosen to leave no padding
     * after itself (long=8, fills a clean 8-byte boundary after 12-byte header).
     */
    static class Animal {
        long id;        // 8 bytes — placed right after the 12-byte header
        String name;    // 4 bytes (compressed ref)
        // Total so far: 12 + 8 + 4 = 24 bytes (clean multiple of 8 — no padding in parent)
    }

    /**
     * Subclass adds a boolean — it starts AFTER the parent's 24 bytes.
     * Then the JVM must pad to align to 8 bytes.
     * Child cannot "reach back" and fill a gap in the parent's layout.
     */
    static class Dog extends Animal {
        boolean isGoodBoy;   // 1 byte — placed at offset 24, then 7 bytes padding
        // Total: 24 (parent) + 1 (boolean) + 7 (padding) = 32 bytes
    }

    /**
     * Subclass with a long — starts at offset 24 (right after parent's clean end).
     * A long needs 8-byte alignment. Offset 24 is 8-byte aligned — no gap needed.
     */
    static class Cat extends Animal {
        long microchipId;    // 8 bytes — fits perfectly at offset 24
        int lives;           // 4 bytes — at offset 32
        // Total: 12 + 8 + 4 + 8 + 4 = 36 → padded to 40 bytes
    }

    /**
     * Worst-case inheritance — parent ends at an odd offset, child pays for it.
     * Parent has a boolean → ends at 13 bytes → padded to 16.
     * Child then starts its fields at offset 16.
     */
    static class VehicleBase {
        boolean isElectric;  // 1 byte → parent instance ends at 13 bytes → padded to 16
    }

    static class ElectricCar extends VehicleBase {
        long batteryCapacity; // 8 bytes — at offset 16 (8-byte aligned — lucked out)
        int range;            // 4 bytes — at offset 24
        // Total: 12 + 1 + [3 pad] + 8 + 4 = 28 → padded to 32 bytes
    }

    /**
     * Three-level hierarchy — shows how layout cascades down the chain.
     */
    static class LivingThing {
        int age;             // 4 bytes — at offset 12 (right after 12-byte header)
    }

    static class Mammal extends LivingThing {
        boolean hasFur;      // 1 byte — at offset 16 → then 3 bytes padding to reach 20
    }

    static class Rabbit extends Mammal {
        int hopSpeed;        // 4 bytes — at offset 20 → 20+4=24, clean
        // Total: 12 + 4 + 1 + [3 pad] + 4 = 24 bytes — no final padding needed
    }

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        showArrayLayouts();
        showSimpleInheritance();
        showWorstCaseInheritance();
        showThreeLevelHierarchy();
        printSummary();
    }

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    static void showArrayLayouts() {
        banner("ARRAY LAYOUTS — The extra length field");

        System.out.println("--- int[] of length 0 ---");
        System.out.println(ClassLayout.parseInstance(new int[0]).toPrintable());

        System.out.println("--- int[] of length 5 ---");
        System.out.println(ClassLayout.parseInstance(new int[5]).toPrintable());

        System.out.println("--- long[] of length 5 ---");
        System.out.println(ClassLayout.parseInstance(new long[5]).toPrintable());

        System.out.println("--- boolean[] of length 5 ---");
        System.out.println(ClassLayout.parseInstance(new boolean[5]).toPrintable());

        System.out.println("--- String[] of length 3 ---");
        System.out.println(ClassLayout.parseInstance(new String[3]).toPrintable());

        /*
         * What to look for in array layouts:
         *
         * int[5] (Java 17/21):
         *   OFFSET  SIZE   TYPE DESCRIPTION
         *        0     4        (object header - mark)
         *        4     4        (object header - mark)
         *        8     4        (object header - class)
         *       12     4        (array length)            ← EXTRA 4 bytes!
         *       16    20    int [I                        ← 5 × 4 bytes of data
         *       36     4        (alignment/padding gap)
         *   Instance size: 40 bytes
         *
         * Key points:
         *   1. Array header = 12 (standard) + 4 (length) = 16 bytes total
         *   2. Array elements start at offset 16
         *   3. int[0] = 16 bytes (all header, zero data) — still costs 16!
         *   4. long[] elements must be 8-byte aligned → start at 16 ✓
         *   5. boolean[] — each boolean still takes 1 byte in an array (not 1 bit)
         *      JVM packs arrays by element type, not by bit
         *
         * On Java 25 with compact headers:
         *   Array header shrinks from 16 → 12 bytes (standard header 8 + length 4)
         */
    }

    static void showSimpleInheritance() {
        banner("SIMPLE INHERITANCE — Animal → Dog, Cat");

        System.out.println("--- Animal (parent) ---");
        System.out.println(ClassLayout.parseInstance(new Animal()).toPrintable());

        System.out.println("--- Dog extends Animal (boolean field) ---");
        Dog d = new Dog();
        d.id = 1L; d.name = "Rex"; d.isGoodBoy = true;
        System.out.println(ClassLayout.parseInstance(d).toPrintable());

        System.out.println("--- Cat extends Animal (long + int fields) ---");
        System.out.println(ClassLayout.parseInstance(new Cat()).toPrintable());

        /*
         * Dog layout (Java 17/21):
         *   OFFSET  SIZE      TYPE DESCRIPTION
         *        0     4           (object header - mark)
         *        4     4           (object header - mark)
         *        8     4           (object header - class)
         *       12     4       int (alignment/padding gap)  ← sometimes a gap appears here
         *       16     8      long Animal.id
         *       24     4    String Animal.name
         *       28     1   boolean Dog.isGoodBoy             ← child field comes AFTER parent
         *       29     3           (loss due to the next object alignment)
         *   Instance size: 32 bytes
         *
         * Critical observation: Dog.isGoodBoy is at offset 28, AFTER all Animal fields.
         * The JVM never mixes parent and child fields.
         * Dog cannot "fill" any gap in Animal's layout — it can only append.
         *
         * This is a fundamental JVM constraint:
         *   → Subclass fields always START after the parent's last field.
         *   → Parent layout is fixed. Child cannot change it.
         *   → A child boolean in a class with a long parent = up to 7 bytes wasted.
         */
    }

    static void showWorstCaseInheritance() {
        banner("WORST-CASE INHERITANCE — VehicleBase (boolean) → ElectricCar");

        System.out.println("--- VehicleBase (just a boolean) ---");
        System.out.println(ClassLayout.parseInstance(new VehicleBase()).toPrintable());

        System.out.println("--- ElectricCar extends VehicleBase ---");
        System.out.println(ClassLayout.parseInstance(new ElectricCar()).toPrintable());

        /*
         * VehicleBase:
         *   OFFSET  SIZE   TYPE DESCRIPTION
         *        0    12        (object header)
         *       12     1   boolean VehicleBase.isElectric
         *       13     3        (padding)
         *   Instance size: 16 bytes
         *
         * ElectricCar:
         *   OFFSET  SIZE   TYPE DESCRIPTION
         *        0    12        (object header)
         *       12     1   boolean VehicleBase.isElectric  ← parent's boolean
         *       13     3        (padding in parent's section — child CANNOT use this)
         *       16     8   long ElectricCar.batteryCapacity ← child starts here
         *       24     4    int ElectricCar.range
         *       28     4        (final padding)
         *   Instance size: 32 bytes
         *
         * The 3 padding bytes at offset 13 are LOCKED in the parent's layout.
         * ElectricCar's fields start fresh at the next 8-byte boundary (16).
         * This is why deep inheritance hierarchies with small boolean fields
         * in parent classes can be surprisingly expensive.
         *
         * Practical advice: if you control the parent, move tiny fields to the child
         * or use a different composition strategy.
         */
    }

    static void showThreeLevelHierarchy() {
        banner("THREE-LEVEL HIERARCHY — LivingThing → Mammal → Rabbit");

        System.out.println("--- LivingThing ---");
        System.out.println(ClassLayout.parseInstance(new LivingThing()).toPrintable());

        System.out.println("--- Mammal extends LivingThing ---");
        System.out.println(ClassLayout.parseInstance(new Mammal()).toPrintable());

        System.out.println("--- Rabbit extends Mammal ---");
        System.out.println(ClassLayout.parseInstance(new Rabbit()).toPrintable());

        /*
         * Rabbit layout:
         *   offset  0–11: object header
         *   offset 12–15: LivingThing.age (int, 4 bytes)
         *   offset 16:    Mammal.hasFur (boolean, 1 byte)
         *   offset 17–19: padding (3 bytes — child cannot fill parent's gap)
         *   offset 20–23: Rabbit.hopSpeed (int, 4 bytes)
         *   Instance size: 24 bytes
         *
         * Each level appends to the previous. Three levels, three separate
         * "sections" in memory. The JVM processes them in class hierarchy order:
         *   1. Scan superclass fields → lay them out
         *   2. Scan this class's fields → lay them out starting after parent
         *   Repeat recursively up to Object.
         */
    }

    static void printSummary() {
        banner("SUMMARY — Chapter 5");
        System.out.println("""
            What we learned:
            
            1. Arrays have a 16-byte header (standard 12 + 4 for array length).
               An empty int[0] still costs 16 bytes before a single element.
               boolean[] stores 1 boolean per byte — not 1 bit per bit.
            
            2. Inheritance is strictly append-only in memory:
               - Parent fields are laid out first, in full
               - Child fields start AFTER the parent's last byte (+ any padding)
               - No field interleaving across class levels
               - Child cannot fill padding gaps in the parent's layout
            
            3. This means a single boolean field in a parent class can:
               - Add 3 bytes of waste to the parent's own layout
               - ALSO push the child's layout to start 4 bytes later
               - Net result: up to 7 bytes wasted due to one boolean in a parent
            
            4. Deep inheritance hierarchies pay a compounding padding tax.
               Shallow hierarchies or composition-over-inheritance is friendlier
               to the memory allocator.
            
            5. On Java 25 with compact headers, array headers shrink from 16 → 12.
               Every array (ArrayList's backing array, HashMap's table, etc.)
               saves 4 bytes — relevant for collection-heavy codebases.
            
            ═══════════════════════════════════════════════════════════════════
            PROJECT COMPLETE — You now have the full JOL output to write:
            "What does a Java object actually look like in memory?"
            Run each chapter on Java 17/21 and Java 25, capture the output,
            and drop it into the Medium article with the narrative from the brief.
            ═══════════════════════════════════════════════════════════════════
            """);
    }

    static void banner(String title) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  " + title);
        System.out.println("=".repeat(70) + "\n");
    }
}
