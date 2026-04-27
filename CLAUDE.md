# CLAUDE.md — JOL Explorer Project Instructions

This file tells Claude Code how to work with this project.
Read this before making any edits.

---

## What this project is

A Java 21 Maven project using the **JOL (Java Object Layout)** library to visually
inspect how objects sit in memory on the JVM. Five chapters, each a standalone
runnable Java class. The output of each chapter feeds a Medium article titled:

> *"What does a Java object actually look like in memory? — A visual deep dive from Java 8 to Java 25"*

---

## Project structure

```
jol-explorer/
├── pom.xml                          ← Maven build file, Java 21, jol-core 0.17
├── CLAUDE.md                        ← this file
└── src/main/java/dev/captain/jol/
    ├── Chapter1_BasicLayout.java    ← field reordering, padding, header cost
    ├── Chapter2_LockStates.java     ← mark word transitions: unlocked→thin→inflated
    ├── Chapter3_CompactHeaders.java ← Java 17 vs Java 25, JEP 519, 12→8 byte header
    ├── Chapter4_MemoryAtScale.java  ← 1M objects, GraphLayout, heap delta
    └── Chapter5_ArraysAndInheritance.java ← array length field, parent-first layout
```

---

## How to build

```bash
# Compile
mvn compile

# Build fat JAR (needed for running on different Java versions)
mvn package

# Run a specific chapter (from Maven, Java 21)
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter1_BasicLayout"
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter2_LockStates"
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter3_CompactHeaders"
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter4_MemoryAtScale"
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter5_ArraysAndInheritance"
```

### Required JVM flag

JOL needs deep JVM access. Always add this when running outside Maven:

```
--add-opens java.base/java.lang=ALL-UNNAMED
```

Maven's exec plugin is already configured with this in pom.xml.

---

## Running on Java 25 for compact header output (Chapter 3 and 4)

Chapter 3 is the core before/after comparison. You need two JVM runs:

### Option A — Docker (recommended, no local Java 25 install needed)

```bash
# Build the fat JAR first
mvn package

# Run on Java 21 (baseline)
docker run --rm \
  -v "$(pwd)/target:/app" \
  eclipse-temurin:21-jre \
  java --add-opens java.base/java.lang=ALL-UNNAMED \
       -cp /app/jol-explorer-1.0.0-jar-with-dependencies.jar \
       dev.captain.jol.Chapter3_CompactHeaders

# Run on Java 25 with compact headers
docker run --rm \
  -v "$(pwd)/target:/app" \
  eclipse-temurin:25-jre \
  java --add-opens java.base/java.lang=ALL-UNNAMED \
       -XX:+UseCompactObjectHeaders \
       -cp /app/jol-explorer-1.0.0-jar-with-dependencies.jar \
       dev.captain.jol.Chapter3_CompactHeaders

# Run Chapter 4 on Java 25 with compact headers
docker run --rm \
  -v "$(pwd)/target:/app" \
  eclipse-temurin:25-jre \
  java --add-opens java.base/java.lang=ALL-UNNAMED \
       -XX:+UseCompactObjectHeaders \
       -Xmx512m \
       -cp /app/jol-explorer-1.0.0-jar-with-dependencies.jar \
       dev.captain.jol.Chapter4_MemoryAtScale
```

### Option B — SDKMAN (if you prefer local install)

```bash
sdk install java 25-open   # or 25.ea-open for EA builds
sdk use java 25-open

java --add-opens java.base/java.lang=ALL-UNNAMED \
     -XX:+UseCompactObjectHeaders \
     -cp target/jol-explorer-1.0.0-jar-with-dependencies.jar \
     dev.captain.jol.Chapter3_CompactHeaders
```

### Java 24 (experimental flag, different syntax)

```bash
java --add-opens java.base/java.lang=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseCompactObjectHeaders \
     -cp target/jol-explorer-1.0.0-jar-with-dependencies.jar \
     dev.captain.jol.Chapter3_CompactHeaders
```

---

## Key JOL APIs used in this project

| API | What it does |
|-----|-------------|
| `ClassLayout.parseInstance(obj).toPrintable()` | Shows field offsets, header size, padding for one object |
| `GraphLayout.parseInstance(obj).toFootprint()` | Summarizes entire object graph by class — used in Chapter 4 |
| `GraphLayout.parseInstance(obj).toPrintable()` | Every object in the graph with address — too verbose for article |
| `VM.current().details()` | JVM-level info: header size, pointer compression, alignment |

---

## Rules for editing this project

### DO

- Keep each chapter self-contained and runnable as a standalone `main()`
- Keep the `banner()` utility method consistent across chapters (same separator style)
- Add comments explaining what each `toPrintable()` block is expected to show
- Use `Thread.ofPlatform()` for platform threads (not `new Thread()`) — Java 21 style
- Use records where a simple data carrier is needed (`record Point(int x, int y) {}`)
- Keep expected output in comments so the article output can be verified

### DO NOT

- Do not add Spring Boot, Spring dependencies, or any web framework
- Do not add logging frameworks (SLF4J, Logback, etc.) — `System.out.println` is intentional
- Do not change the package from `dev.captain.jol`
- Do not change the chapter file names — they are referenced in CLAUDE.md and the article
- Do not use `new Thread()` — use `Thread.ofPlatform()` or `Thread.ofVirtual()`
- Do not remove the `--add-opens` note from chapter Javadocs
- Do not upgrade jol-core beyond 0.17 without testing — API changes between versions

### When adding a new class to an existing chapter

Keep it as a `static` nested class inside the chapter file (like `Chapter1_BasicLayout.MixedFieldPojo`).
This keeps each chapter file self-contained and avoids cross-chapter imports.

### When adding a new chapter

- Name it `Chapter6_<Topic>.java` following the existing convention
- Add it to the exec plugin's list in `pom.xml`
- Add it to this CLAUDE.md under "Project structure"

---

## Java version compatibility notes

| Feature | Min Java version |
|---------|-----------------|
| `Thread.ofPlatform()` | Java 21 |
| `record` | Java 16 |
| `UseCompactObjectHeaders` (experimental) | Java 24 |
| `UseCompactObjectHeaders` (production) | Java 25 |
| `UseCompactObjectHeaders` (default on) | Java 26 |
| Biased locking | Java 8–17 only (removed in Java 18) |
| Text blocks (`"""`) | Java 15 |

This project compiles with `--source 21 --target 21`.
Do not use Java 22+ language features (unnamed patterns, etc.) without updating `pom.xml`.

---

## Troubleshooting

### "InaccessibleObjectException" or "Unable to make field accessible"

Add: `--add-opens java.base/java.lang=ALL-UNNAMED` to your JVM args.
The Maven exec plugin already includes this.

### Chapter 2 doesn't show inflation

Lock inflation requires real contention. If Demo 2 shows thin lock throughout:
- Increase `Thread.sleep(200)` to `500` to give the JVM more time to inflate
- Ensure thread2 is actually blocked before printing (the `CountDownLatch` timing may need adjustment)
- Check Java version — behavior differs between Java 17 and Java 21

### Chapter 3 shows 12 bytes on Java 25

Compact headers must be explicitly enabled on Java 25 (opt-in):
```
-XX:+UseCompactObjectHeaders
```
On Java 26, it will be on by default.

### Chapter 4 runs out of memory

Reduce `LARGE_SCALE` from `1_000_000` to `500_000`.
Or add `-Xmx512m` to the JVM args.

### Output looks different from the comments

JVM behavior can vary slightly between JVM implementations and patch versions.
The comments describe OpenJDK behavior. GraalVM may show different offsets.
The key patterns (field order, padding rules, header size) remain the same.

---

## Article output checklist

Run these commands and save output to `article-output/` directory:

```bash
mkdir article-output

mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter1_BasicLayout" \
  > article-output/chapter1-java21.txt 2>&1

mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter2_LockStates" \
  > article-output/chapter2-java21.txt 2>&1

mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter3_CompactHeaders" \
  > article-output/chapter3-java21.txt 2>&1

# Then on Java 25 container:
# > article-output/chapter3-java25-compact.txt

mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter4_MemoryAtScale" \
  > article-output/chapter4-java21.txt 2>&1

mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter5_ArraysAndInheritance" \
  > article-output/chapter5-java21.txt 2>&1
```

The `article-output/` directory is gitignored — it contains machine-specific output.

---

## Git

```bash
git init
git add .
git commit -m "feat: initial JOL explorer project — 5 chapters"
```

Suggested `.gitignore` additions:
```
target/
article-output/
*.class
```
