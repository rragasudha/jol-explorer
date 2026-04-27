# JOL Explorer — JVM Object Internals

A Java 21 project using [JOL (Java Object Layout)](https://openjdk.org/projects/code-tools/jol/) to visually inspect how objects sit in memory on the JVM.

Five standalone chapters, each covering a different aspect of JVM memory layout — written to accompany the Medium article:

> **"What does a Java object actually look like in memory? — A visual deep dive from Java 8 to Java 25"**

---

## Requirements

- Java 21+
- Maven 3.8+
- Docker (optional, for running Chapter 3/4 on Java 25)

---

## Project structure

```
src/main/java/dev/captain/jol/
├── Chapter1_BasicLayout.java        ← field reordering, padding, header cost
├── Chapter2_LockStates.java         ← mark word: unlocked → thin lock → inflated
├── Chapter3_CompactHeaders.java     ← JEP 519: 12-byte header vs 8-byte compact header
├── Chapter4_MemoryAtScale.java      ← 1M objects, GraphLayout, heap delta
└── Chapter5_ArraysAndInheritance.java ← array length field, parent-first layout
```

---

## Build

```bash
mvn compile

# Fat JAR (needed for Docker / Java 25 runs)
mvn package
```

---

## Run

```bash
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter1_BasicLayout"
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter2_LockStates"
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter3_CompactHeaders"
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter4_MemoryAtScale"
mvn exec:java -Dexec.mainClass="dev.captain.jol.Chapter5_ArraysAndInheritance"
```

The exec plugin is pre-configured with `--add-opens java.base/java.lang=ALL-UNNAMED`, which JOL requires for deep JVM access.

---

## Chapter 3 & 4 on Java 25 (compact headers)

JEP 519 shrinks the object header from 12 → 8 bytes. It's opt-in on Java 25, default on Java 26.

```bash
# Java 21 baseline
docker run --rm -v "$(pwd)/target:/app" eclipse-temurin:21-jre \
  java --add-opens java.base/java.lang=ALL-UNNAMED \
       -cp /app/jol-explorer-1.0.0-jar-with-dependencies.jar \
       dev.captain.jol.Chapter3_CompactHeaders

# Java 25 with compact headers
docker run --rm -v "$(pwd)/target:/app" eclipse-temurin:25-jre \
  java --add-opens java.base/java.lang=ALL-UNNAMED \
       -XX:+UseCompactObjectHeaders \
       -cp /app/jol-explorer-1.0.0-jar-with-dependencies.jar \
       dev.captain.jol.Chapter3_CompactHeaders
```

---

## What each chapter demonstrates

| Chapter | Topic | Key insight |
|---------|-------|-------------|
| 1 | Basic layout & field reordering | JVM ignores declaration order; every object pays a 12-byte header tax |
| 2 | Lock state transitions | Mark word encodes lock state: unlocked → thin lock → inflated |
| 3 | Compact object headers | JEP 519 drops header from 12 → 8 bytes; EmptyObject shrinks from 16 → 8 bytes |
| 4 | Memory at scale | 1M Point objects = ~24 MB; compact headers save ~4 MB |
| 5 | Arrays & inheritance | Arrays have a length field; parent fields come first in memory |

---

## Troubleshooting

**`InaccessibleObjectException`** — add `--add-opens java.base/java.lang=ALL-UNNAMED` (Maven exec plugin already includes this).

**Chapter 2 doesn't show lock inflation** — increase `Thread.sleep` to 500 ms or check Java version (biased locking was removed in Java 18).

**Chapter 3 still shows 12 bytes on Java 25** — add `-XX:+UseCompactObjectHeaders` explicitly (opt-in until Java 26).

**Chapter 4 OOM** — reduce `LARGE_SCALE` from `1_000_000` to `500_000`, or pass `-Xmx512m`.
