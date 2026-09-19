---
name: agent
description: Edit loop, rules and command reference for working on a Mycelium codebase through myc.
---
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
4. Verify in isolation: `myc test <manifest> <cell>` runs it with a generated
   input and reports output, matched dispatch label and phase-tagged errors.
   Then `--input '{...}'` with boundary inputs: empty collections, nil-able
   keys, zero/negative numbers.
5. Re-run `myc status` to confirm the cell passes; move to the next failing
   cell.

Load handler namespaces first with `--require <ns>` (repeatable), otherwise
every cell reads as `pending` because no handler is registered.

For structural edits: `myc refs <manifest> <cell>` shows every site a change
touches, `myc hash` then `myc patch --expect-hash <h> --op ...` applies it
checked. Batch related ops in one patch (add a cell and wire it); validation
runs once for the batch. A successful patch is already validated and
written — do not run `myc validate` or `myc diff` just to confirm.

End to end: `myc run <manifest> --input '{...}'` prints the path taken and
the result; `--stubs` runs unregistered cells as identity to check routing
before handlers exist.

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
- Fetch each skill topic at most once per session; the content is fixed for
  the installed version. `myc skills` lists topics with sizes.
- Text output is the default. Use `--json` only when another tool must parse
  stable fields.

## Commands {#commands}

```
myc validate <path> [--lenient]  structural validation (exit 0/1); strict like load-manifest
myc hash <path>                  content hash — use with `myc patch --expect-hash`
myc status <path>                per-cell implementation status (exit 3 unless green)
myc test <path> <cell> [--input <edn>]
                                 run one cell in isolation (exit 3 on failure)
myc brief <path> <cell>          one cell's implementation brief
myc briefs <path>                all briefs
myc region <path> <name>         subgraph cluster brief
myc refs <path> <cell>           every reference site for a cell
myc diff <a> <b>                 semantic diff of two manifests (exit 1 if different)
myc run <path> --input <edn> [--resources <ns/var>] [--stubs]
                                 run the workflow: trace, result, data (exit 3 on error)
myc plan <path>                  build order (cells are independent by default)
myc paths <path>                 every start-to-terminal path
myc schema <path>                data keys available at each cell
myc dot <path>                   DOT rendering
myc patch <path> --op <name> [--<arg> <v> ...] [--expect-hash <h>] [--dry-run]
                                 checked edit, keeps comments/layout; `--op help` lists ops:
                                 rename-cell add-cell remove-cell set-edge delete-edge
                                 set-cell-field set-dispatches
myc skills [get <topic> [--section <id>]]
                                 this documentation, one topic or section
```

Every command takes `--json` and `--require <ns>` (repeatable) anywhere in
the arguments. Cell names may carry a leading colon (`:start`).

Exit codes: 0 ok, 1 failure, 3 status-not-green / test failed, 64 usage,
66 file missing.
