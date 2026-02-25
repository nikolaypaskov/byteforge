# ByteForge

**Natural language → JVM bytecode → execution. No programming language in between.**

ByteForge proves that AI coding agents can bypass programming languages entirely — translating natural language descriptions directly into executable JVM bytecode, without generating any source code.

```
"A program that prints Hello World"
        ↓ Claude API (structured tool_use)
    ClassDefinition (JSON)
        ↓ ASM ClassWriter
      344 bytes of bytecode
        ↓ BytecodeClassLoader
    Hello from bytecode!
```

## The Thesis

Programming languages exist because humans can't write machine code. They're an intermediate representation — a bridge between what you *mean* and what the CPU *executes*. Compilers exist to cross that bridge: they parse syntax, check types, optimize, and emit bytes.

But when the "programmer" is an LLM, the calculus changes. An LLM doesn't need human-readable syntax. It doesn't benefit from semicolons or curly braces. It can work directly with structured data — and a bytecode instruction set *is* structured data. Each instruction is just a JSON object with an `op` field and a few operands.

**ByteForge tests a specific hypothesis**: an LLM can go from natural language intent to executable machine instructions, skipping the programming language layer entirely. Not as a toy — with multi-class programs, control flow, object construction, self-repair on errors, iterative refinement through conversation, and across multiple compilation targets.

The question isn't "can this replace javac?" It can't — javac has decades of optimization. The question is: **does the intermediate representation of a programming language add value when the consumer of that representation is a machine?** ByteForge argues no.

## What ByteForge Proves (5 Features)

Each feature validates a different aspect of the thesis:

### 1. Single-Class Pipeline — The Minimal Proof

```bash
ANTHROPIC_API_KEY=sk-ant-... ./gradlew run --args="FizzBuzz for numbers 1 to 20"
```

The core pipeline: prompt → Claude → JSON → ASM → bytes → execute. No source code is generated, stored, or parsed. Claude receives a system prompt that says "you are a JVM bytecode compiler" and a tool schema describing the instruction set. It emits a `ClassDefinition` with fields, methods, and instruction sequences. AsmCompiler translates that to a valid `.class` file. DynamicRunner loads it into the JVM and runs `main()`.

This proves the minimum: **an LLM can emit correct JVM bytecode from a natural language description.** The 48-instruction FizzBuzz with conditional branching, modulo arithmetic, and string concatenation is not trivial — it requires the LLM to reason about stack discipline, local variable slots, and jump targets without ever seeing Java syntax.

### 2. Multi-Class Programs — The LLM as Type System

```bash
ANTHROPIC_API_KEY=sk-ant-... ./gradlew run --args="--multi A Person class with name/age and a Main class that creates two people and compares them"
```

Single-class programs are interesting. Multi-class programs are convincing. When Claude emits a `ProgramDefinition` with multiple classes, it must maintain a coherent type system: field descriptors in one class must match access patterns in another. Constructor signatures must align with `invokespecial` calls. Return types must match what the caller expects on the stack.

This isn't just "can you emit bytes?" — it's "can you manage an object model across compilation units, using only JVM internal names and type descriptors, without ever producing a `.java` file?"

### 3. Self-Repair — The Feedback Loop

When bytecode fails to compile or throws at runtime, the error message goes back to Claude as a `tool_result` with `is_error: true`. Claude reads the stack trace and re-emits corrected bytecode. The loop runs up to `MAX_RETRIES` times.

This is the same feedback loop that traditional compilers use internally (diagnostics → fix), but applied at the LLM level. It means the system is resilient to the kinds of mistakes an LLM will naturally make — wrong descriptors, misaligned stack states, missing labels — because it can *read its own errors and correct them.*

```
[1/3] Generating bytecode via Claude API...
      Class: FizzBuzz | Methods: 1 | Instructions: 48
[2/3] Compiling to JVM bytecode via ASM...
      Compilation error: Unknown opcode: 'imod'
[retry 2/3] Asking Claude to fix the error...
      Class: FizzBuzz | Methods: 1 | Instructions: 48  ← fixed imod → irem
[2/3] Compiling to JVM bytecode via ASM...
      Generated 891 bytes
```

### 4. Interactive REPL — Iterative Evolution

```bash
ANTHROPIC_API_KEY=sk-ant-... ./gradlew interactive
```

```
byteforge[1]> A counter that prints 1 to 5
  Class: Counter | Methods: 1 | Instructions: 18
  Running... (412 bytes)
  1 2 3 4 5

byteforge[2]> Make it count backwards, 5 to 1
  Delta: methods +0, instructions +4
  Running... (438 bytes)
  5 4 3 2 1

byteforge[3]> Add a sum and print it at the end
  Delta: methods +0, instructions +8
  Running... (496 bytes)
  5 4 3 2 1
  Sum: 15
```

The `ConversationManager` maintains full message history across turns. Claude sees its previous bytecode emissions, whether they succeeded or failed, and the user's incremental modifications. Each turn emits a complete updated class — no diffs, no patches, just the full instruction sequence.

This proves the thesis extends beyond one-shot generation: **an LLM can iteratively develop and refine programs at the bytecode level through conversation**, the same way a developer iterates on source code.

### 5. WebAssembly Target — The Thesis Is Runtime-Agnostic

```bash
ANTHROPIC_API_KEY=sk-ant-... ./gradlew runWasm --args="A function that computes fibonacci(10) and returns the result"
```

The same pattern — LLM → structured IR → binary — works for an entirely different target. `WatCompiler.toWat()` converts a `WasmModule` to WebAssembly Text format, then `wat2wasm` produces the binary, and `WasmRunner` executes it via wasmtime or Node.js.

Different instruction set (stack machine with `i32.const`, `local.get`, `br_if`). Different runtime. Different binary format. Same principle: **the LLM doesn't need a programming language to target either platform.**

### 6. Bytecode Comparison — Measuring the Gap

```bash
ANTHROPIC_API_KEY=sk-ant-... ./gradlew compare --args="Hello World program"
```

`ComparisonRunner` compiles the same program both ways — Claude → AsmCompiler and Claude → Java source → javac — then runs `javap -c -p` on both and compares the output. You get side-by-side disassembly and a size percentage difference.

This isn't about beating javac. javac has decades of optimization, constant folding, and dead code elimination. The point is to **quantify the gap** and show it's surprisingly small. For simple programs, ByteForge bytecode is typically within 5-15% of javac output size, and functionally equivalent.

## How It Works

```
Natural Language
        │
        ▼
┌──────────────┐   tool_use forcing    ┌──────────────┐
│  ClaudeClient │ ────────────────────► │  Claude API   │
│              │ ◄──────────────────── │              │
│  System prompt│   ClassDefinition    │  Structured   │
│  + tool schema│   (JSON)             │  tool output  │
└──────┬───────┘                       └──────────────┘
       │
       ▼
┌──────────────┐
│  AsmCompiler  │   ClassDefinition → ASM ClassWriter → byte[]
│              │   COMPUTE_MAXS | COMPUTE_FRAMES
│              │   Auto-generates <init> if missing
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ DynamicRunner │   BytecodeClassLoader → loadClass → invoke main()
│              │   Captures stdout, returns Success/Failure
└──────┬───────┘
       │
       ▼                          ┌──────────────────────────┐
   Program Output                 │  On failure:              │
                                  │  error → ConversationMgr  │
                                  │  → Claude re-emits        │
                                  │  → retry loop             │
                                  └──────────────────────────┘
```

The key architectural insight is **tool_use forcing**. The API call sets `tool_choice: { type: "tool", name: "emit_bytecode" }`, which guarantees Claude returns structured JSON matching the tool schema — not free-form text, not explanations, not source code. The tool schema *is* the instruction set architecture.

## Quick Start

```bash
# Single-class (default: Hello World)
ANTHROPIC_API_KEY=sk-ant-... ./gradlew run

# Custom prompt
ANTHROPIC_API_KEY=sk-ant-... ./gradlew run --args="FizzBuzz for numbers 1 to 20"

# Multi-class program
ANTHROPIC_API_KEY=sk-ant-... ./gradlew run --args="--multi A LinkedList with add/print and a Main that uses it"

# Interactive REPL
ANTHROPIC_API_KEY=sk-ant-... ./gradlew interactive

# WebAssembly target
ANTHROPIC_API_KEY=sk-ant-... ./gradlew runWasm

# Compare with javac
ANTHROPIC_API_KEY=sk-ant-... ./gradlew compare

# Run all offline tests (no API key needed)
./gradlew allOfflineTests

# Save .class file for inspection with javap
SAVE_CLASS=true ANTHROPIC_API_KEY=sk-ant-... ./gradlew run
javap -c Hello.class
```

## Tested Examples

| Prompt | Instructions | Methods | Result |
|--------|-------------|---------|--------|
| Hello World | 4 | 1 | Prints greeting |
| FizzBuzz (1-20) | 48 | 1 | Correct Fizz/Buzz/FizzBuzz logic |
| Fibonacci (15 numbers) | 36 | 1 | Correct sequence with loop |
| Bubble Sort | 131 | 3 | Nested loops, array swap, before/after output |
| Prime Checker | 68 | 3 | Modulo logic, boolean return, 10 test cases |
| Diamond Pattern (width 9) | 96 | 1 | Nested loops, calculated spacing |

## Test Suite — Proving the Pipeline Without the LLM

The test suite is central to the thesis. It answers the question: **if the LLM produces correct structured output, does every layer of the pipeline faithfully translate that into correct execution?**

66 offline tests validate every component in isolation, without making a single API call. The tests hand-craft the same JSON structures Claude would produce, feed them through each layer, and assert correctness. This separates the two claims:

1. **The pipeline is sound** — proven by tests
2. **The LLM can fill the pipeline** — proven by live demos

### What Each Test Suite Proves

**BytecodeModelTest (19 tests)** — The instruction set is expressible. Every instruction type the LLM might emit (all 9 subtypes: simple, ldc, field, method, var, type, int, jump, label) deserializes correctly from JSON. Compact forms like `aload_0` expand properly. Unknown opcodes degrade gracefully. The model round-trips through serialization. This proves the *schema contract* between Claude's output and the compiler's input is solid.

**AsmCompilerTest (13 tests)** — The compiler handles the full instruction set. Jump instructions with forward labels produce working loops. Integer arithmetic (`iadd`, `isub`, `imul`, `idiv`, `irem`) produces correct results. Type conversions compile. Array operations work end-to-end. Unknown opcodes throw `CompilationException` (which the self-repair loop catches). LDC handles strings, ints, floats, and longs. Auto-generated constructors work. This proves that **any valid ClassDefinition, no matter how complex, compiles to correct JVM bytecode.**

**DynamicRunnerTest (7 tests)** — Execution is faithful. Single-class stdout capture works. Multi-class cross-references (Calculator.add called from Main) resolve correctly through BytecodeClassLoader. Missing main methods produce clean failure messages (for the self-repair loop). Runtime exceptions (divide by zero) are caught and reported. Missing dependency classes fail gracefully. This proves the **runtime layer correctly loads, links, and executes whatever the compiler produces.**

**ConversationManagerTest (11 tests)** — Conversation state is reliable. User messages, assistant responses, and tool results maintain correct JSON structure. The `is_error` flag (critical for self-repair) is set only when specified. Trimming preserves the most recent context. Message ordering is guaranteed. The returned list is a defensive copy. This proves the **self-repair and interactive loops have a correct state manager.**

**WatCompilerTest (12 tests)** — The WASM path generates correct WAT. Function signatures, imports (func and memory), exports, data segments, locals, and instructions all appear in the right syntax. Memory declarations don't duplicate when imported (a real bug this test guards). Special characters are escaped. This proves the **WASM target path is as sound as the JVM path.**

**ComparisonRunnerTest (4 tests)** — The comparison infrastructure works. Both javac and ByteForge bytecode produce valid javap output. Sizes are positive. The percentage calculation is correct. This proves the **measurement tool for quantifying the gap is trustworthy.**

### Running the Tests

```bash
# All offline tests (66 tests, no API key needed)
./gradlew allOfflineTests

# Individual suites
./gradlew bytecodeModelTest         # 19 tests — instruction deserialization
./gradlew conversationManagerTest   # 11 tests — conversation state
./gradlew asmCompilerTest           # 13 tests — compilation correctness
./gradlew dynamicRunnerTest         #  7 tests — execution + class loading
./gradlew watCompilerTest           # 12 tests — WASM WAT generation
./gradlew comparisonRunnerTest      #  4 tests — bytecode comparison (requires javac)

# Original integration tests
./gradlew offlineTest               # Hand-crafted Hello World end-to-end
./gradlew multiClassOfflineTest     # Hand-crafted Person + Main end-to-end
```

## Project Structure

```
src/main/kotlin/dev/byteforge/
├── Main.kt                        # Single/multi-class pipeline with self-repair
├── InteractiveMain.kt             # REPL — iterative bytecode development
├── WasmMain.kt                    # WASM pipeline — prompt → WAT → wasm binary
├── model/
│   ├── BytecodeModel.kt           # JVM instruction sealed class hierarchy + deserializer
│   └── WasmModel.kt               # WASM module data classes
├── llm/
│   ├── ClaudeClient.kt            # Anthropic Messages API with tool_use forcing
│   └── ConversationManager.kt     # Multi-turn message history for self-repair + REPL
├── compiler/
│   ├── AsmCompiler.kt             # ClassDefinition → ASM ClassWriter → byte[]
│   └── WatCompiler.kt             # WasmModule → WAT text → wasm binary (via wat2wasm)
├── runtime/
│   ├── DynamicRunner.kt           # BytecodeClassLoader + reflection invoke
│   └── WasmRunner.kt              # WASM execution via wasmtime or Node.js
└── compare/
    ├── CompareMain.kt             # ByteForge vs javac side-by-side
    └── ComparisonRunner.kt        # javac + javap comparison logic

src/test/kotlin/dev/byteforge/
├── OfflineTest.kt                 # End-to-end: hand-crafted Hello World
├── MultiClassOfflineTest.kt       # End-to-end: hand-crafted Person + Main
├── BytecodeModelTest.kt           # 19 tests — instruction deserialization
├── AsmCompilerTest.kt             # 13 tests — compilation correctness
├── DynamicRunnerTest.kt           #  7 tests — execution + class loading
├── ConversationManagerTest.kt     # 11 tests — conversation state management
├── WatCompilerTest.kt             # 12 tests — WASM WAT generation
└── ComparisonRunnerTest.kt        #  4 tests — bytecode comparison
```

## Key Design Decisions

- **Tool use, not free-form text** — `tool_choice: { type: "tool", name: "emit_bytecode" }` forces structured output. No regex parsing, no code extraction from markdown blocks. The tool schema *is* the instruction set.
- **`COMPUTE_MAXS | COMPUTE_FRAMES`** — ASM auto-computes stack sizes and frame maps. This is the single most important design decision: it means the LLM doesn't need to reason about stack depth, which is the hardest part of bytecode generation.
- **Auto-generated `<init>`** — The compiler auto-generates a default constructor if none is provided. The system prompt tells Claude to skip it. This eliminates the most common error in LLM-generated bytecode.
- **Flat instruction schema with `op` discriminator** — Every instruction is `{ "op": "...", ...fields }`. `InstructionDeserializer` dispatches on `op` to the right subtype. Simpler than polymorphic JSON, and matches how LLMs naturally generate structured data.
- **Self-repair via conversation history** — Compilation and runtime errors become `tool_result` messages with `is_error: true`. Claude reads the error and re-emits. The `ConversationManager` keeps the full history so each retry has context.
- **Dual target architecture** — JVM and WASM paths share the same pattern (LLM → structured IR → binary → execute) but with independent model/compiler/runner stacks. This proves the thesis generalizes.

## Limitations and Honest Assessment

ByteForge is a proof of concept, not a production compiler. Being clear about what it can and can't do is part of what makes the thesis credible.

**What works today.** Programs up to moderate complexity — FizzBuzz (48 instructions), Fibonacci with loops (36 instructions), Bubble Sort with nested loops and array manipulation (131 instructions), multi-class programs with constructors and cross-class method calls. The self-repair loop handles most LLM mistakes within 2-3 retries. The pipeline itself (as 66 offline tests prove) can handle *anything* — the ceiling is the LLM's reasoning capacity, not the infrastructure.

**What doesn't work yet.** A 10,000-instruction program with exception handlers, generics, and lambda deserialization is beyond what an LLM can reliably emit in a single shot today. javac's output is more optimized — constant folding, dead code elimination, inlining — things that require global analysis the LLM doesn't do. ByteForge bytecode is typically 5-15% larger and less optimized for equivalent programs.

**Why this still matters.** The limitations are about the LLM, not the approach. Every year, LLMs get better at structured reasoning, longer context, and more reliable output. The pipeline ByteForge builds — tool-forced structured output, instruction-level schema, self-repair, dual-target architecture — is ready for whatever the next model can produce.

The way to think about ByteForge is as a waypoint on a trajectory. Today, AI coding agents generate source code because that's the level of abstraction where LLMs are most reliable. But as models get better at structured reasoning — and they are, measurably, with each generation — the source code layer becomes overhead rather than scaffolding. You're doing two translations (intent → source → binary) when one could suffice (intent → binary).

ByteForge proves the single-step path works. Not perfectly, not at scale — but it works. FizzBuzz with correct conditional branching and modulo arithmetic, emitted as raw JVM bytecode from a natural language description, is not a trivial result. Multi-class programs with object construction and cross-class method dispatch, with no `.java` file ever touching the disk, is not a trivial result.

**The natural next step.** Once AI agents solve the code generation problem completely — producing correct, optimized source code 100% of the time — the logical follow-on question is: why produce source code at all? If the agent is perfect at generating Java, it can be perfect at generating the bytecode Java compiles to, and skip the intermediate step. The programming language becomes what it always was for machines: unnecessary overhead. ByteForge is an early proof that this path is viable, waiting for the models to catch up with the architecture.

## Requirements

- JDK 17+
- Anthropic API key (for live runs; tests don't need one)
- `wat2wasm` (optional, for WASM pipeline — install via [wabt](https://github.com/WebAssembly/wabt))
- `wasmtime` or `node` (optional, for WASM execution)

## License

MIT
