# Mycelium Agent Skill

How a coding agent works on a Mycelium codebase: the EDN manifest is the
program database. Query it with `myc`; don't read whole source files when a
scoped command answers the question.

## Edit Loop {#edit-loop}

1. Locate work: `myc status <manifest>` — exit 3 means not all cells pass.
   `--json` for machine output.
2. Get the contract for one cell: `myc brief <manifest> <cell>`. The brief is
   self-contained: schema, required resources, example data, dispatch labels.
   Do not read other cells' code — the contract is all a cell may know.
3. Implement or fix the cell's handler (a `defcell`/`cell-spec` method).
4. Verify in isolation with generated or typical inputs. Boundary inputs too:
   empty collections, nil-able keys, zero/negative numbers.
5. Re-run `myc status` to confirm the cell passes; move to the next failing
   cell.

Load handler namespaces first with `--require <ns>` (repeatable), otherwise
every cell reads as `pending` because no handler is registered.

## Rules {#rules}

- Scoped reads over bulk reads: prefer `myc brief`/`myc region`/`myc paths`
  to slurping the manifest or implementation files.
- After `myc brief <cell>` succeeds you already have the contract; don't
  re-run `myc status` just to confirm the read.
- The manifest is the source of truth for schemas. If handler and manifest
  disagree, the manifest wins — fix the handler.
- Cells return only new keys; key propagation merges upstream data.
- Never require another cell's namespace from a cell. Data flow goes through
  edges, not imports.
- Errors are data: set a key and let dispatch predicates route. Don't throw
  for expected failures.

## Commands {#commands}

```
myc validate <path>            structural validation (exit 0/1)
myc hash <path>                content hash — use with `myc patch --expect-hash`
myc status <path> [--json]     per-cell implementation status (exit 3 unless green)
myc brief <path> <cell>        one cell's implementation brief
myc briefs <path>              all briefs
myc region <path> <name>       subgraph cluster brief
myc plan <path>                build order (cells are independent by default)
myc paths <path>               every start-to-terminal path
myc schema <path>              data keys available at each cell
myc dot <path>                 DOT rendering
myc skills get <topic> [--section <id>]
                               this documentation, one topic or section
```

Exit codes: 0 ok, 1 failure, 3 status-not-green, 64 usage, 66 file missing.
