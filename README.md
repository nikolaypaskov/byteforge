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

## Why?

Programming languages exist as a human-readable intermediate between intent and execution. When the "programmer" is an LLM, that intermediate becomes unnecessary. ByteForge is a proof of concept for this thesis: **intent → bytes → execution**, no syntax required.

## How It Works

1. **ClaudeClient** sends your natural language prompt to the Anthropic Messages API with a forced `emit_bytecode` tool call, so Claude returns structured JSON describing JVM bytecode instructions — not source code, not explanations.

2. **AsmCompiler** takes that `ClassDefinition` and translates it into a valid `.class` file using the ASM library's `ClassWriter` with `COMPUTE_MAXS | COMPUTE_FRAMES` (ASM auto-computes stack sizes and frame maps).

3. **DynamicRunner** loads the raw bytes via a custom `BytecodeClassLoader`, finds `main(String[])` via reflection, and executes it.

## Quick Start

```bash
# Run with default "Hello World" prompt
ANTHROPIC_API_KEY=sk-ant-... ./gradlew run

# Run with a custom prompt
ANTHROPIC_API_KEY=sk-ant-... ./gradlew run --args="FizzBuzz for numbers 1 to 20"

# Run offline test (no API key needed)
./gradlew offlineTest

# Save .class file for inspection
SAVE_CLASS=true ANTHROPIC_API_KEY=sk-ant-... ./gradlew run
javap -c Hello.class
```

## Tested Examples

| Prompt | Instructions | Methods | Result |
|--------|-------------|---------|--------|
| Hello World | 4 | 1 | Prints greeting |
| FizzBuzz (1–20) | 48 | 1 | Correct Fizz/Buzz/FizzBuzz logic |
| Fibonacci (15 numbers) | 36 | 1 | Correct sequence with loop |
| Bubble Sort | 131 | 3 | Nested loops, array swap, before/after output |
| Prime Checker | 68 | 3 | Modulo logic, boolean return, 10 test cases |
| Diamond Pattern (width 9) | 96 | 1 | Nested loops, calculated spacing |

## Project Structure

```
src/main/kotlin/dev/byteforge/
├── Main.kt                   # Entry point — 3-step pipeline with progress output
├── model/
│   └── BytecodeModel.kt      # Data classes + InstructionDeserializer (sealed class keyed on "op")
├── llm/
│   └── ClaudeClient.kt       # Anthropic Messages API with tool_use forcing emit_bytecode
├── compiler/
│   └── AsmCompiler.kt        # ClassDefinition → ASM ClassWriter → byte[]
└── runtime/
    └── DynamicRunner.kt      # BytecodeClassLoader + reflection invoke of main()
```

## Key Design Decisions

- **Tool use, not free-form text** — forces structured output; no regex parsing needed
- **`tool_choice` forcing** — prevents Claude from responding with explanations instead of bytecode
- **`COMPUTE_MAXS | COMPUTE_FRAMES`** — ASM auto-computes stack sizes; Claude doesn't need to
- **Auto-generated `<init>`** — eliminates constructor errors; system prompt tells Claude to skip it
- **Flat instruction schema with `op` discriminator** — simpler than polymorphic JSON; `InstructionDeserializer` routes by opcode

## Requirements

- JDK 17+
- Anthropic API key (for end-to-end runs)

## License

MIT
