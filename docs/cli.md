# Agent CLI

`myc` is the agent-facing command line: scoped reads over an EDN manifest, checked edits, and bundled skills. The manifest is the program database — every command answers a specific question instead of dumping whole files into context.

## Setup

From a mycelium checkout, from any directory:

```sh
/path/to/mycelium/bin/myc <command>
```

Manifest paths are relative to where you run it. If the current directory has a `deps.edn` (an app that depends on mycelium), it is merged in, so `--require` can load the app's cell namespaces.

As a library dependency, invoke the entry point directly:

```sh
clojure -M -m mycelium.cli <command>
```

## Commands

```sh
myc validate <path> [--lenient]   structural validation; exit 0/1
myc hash <path>                   content hash of the file (sha-256 over canonical EDN)
myc status <path>                 per-cell implementation status; exit 3 unless all passing
myc test <path> <cell> [--input <edn>]
                                  run one cell in isolation; exit 3 on failure
myc brief <path> <cell>           self-contained implementation brief for one cell
myc briefs <path>                 briefs for every cell
myc region <path> <name>          scoped brief for a :regions cluster
myc refs <path> <cell>            every place a cell is referenced
myc plan <path>                   build order
myc paths <path>                  every start-to-terminal path
myc schema <path>                 data keys available at each cell
myc dot <path>                    DOT graph rendering
myc patch <path> --op <name> [--<arg> <value> ...] [--expect-hash <hash>] [--dry-run]
                                  checked manifest edit; `myc patch --op help` lists ops
myc skills                        list bundled skill topics with sizes
myc skills get <topic> [--section <id>]
                                  print one skill topic or section
```

Flags work in any position:

- `--require <ns>` (repeatable) loads handler namespaces before querying, so registered cells are visible to `status`, `test`, `schema`, and to `patch` on manifests using `:schema :inherit`.
- `--json` switches every command to a machine-readable envelope: `{"ok": bool, "exit": n, ...}` carrying the command's structured fields on success, or `"error"` and `"data"` on failure. Use it when another tool parses the output; the text form is the agent default.
- Cell names accept a leading colon, so `myc brief m.edn :start` works exactly like `myc brief m.edn start` — copy names straight out of `status` and `paths` output.

Exit codes: 0 success, 1 failure, 3 status not green / cell test failed, 64 usage error, 66 manifest file missing.

### validate

Strict by default, matching `load-manifest`: every cell needs an `:on-error` declaration (`nil` is fine). `--lenient` relaxes that for legacy manifests. The other commands are always lenient — `validate` is the gate, the rest are reads.

Read errors (unparseable EDN) and validation errors (`Invalid manifest: ...`) both exit 1 and are distinguished in the message.

### test

Runs one cell through `dev/test-cell` with full schema validation and prints the input, output, matched dispatch label, and any errors with their phase (`input`, `output`, `dispatch`). Dispatch predicates from the manifest are evaluated (SCI, same as the runtime), so routing is checked too.

```sh
myc test resources/workflows/checkout.edn validate --input '{:cart-id 7}' --require app.cells
```

Without `--input` the cell gets a generated input from its input schema — the same input `status` uses. A cell whose handler is not registered exits 1 with a hint to `--require` its namespace.

### refs

Lists every reference site for a cell, one per line with its role and `get-in` path:

```
:process — 4 references
  definition           :cells :process
  edges-out            :edges :process
  edges-in             :edges :start :success
  dispatches           :dispatches :process
```

Roles cover `:cells`, `:on-error` targets, edges (both directions), dispatches, joins (name and membership), regions, constraints, timeouts, resilience, error groups, `:pipeline`, and fragment `:as` aliases and `:exits`. This is exactly the set `patch --op rename-cell` rewrites, so `refs` is how you review a rename before and after.

## Checked edits

`myc patch` applies structural operations to the manifest *as written*: `:fragments`, `:pipeline` shorthand and `:schema :inherit` stay as they are. Each op validates the manifest before the edit and re-validates the result (fragments expanded) before anything is written, so a rejected edit leaves the file untouched.

```sh
myc patch resources/workflows/checkout.edn --op rename-cell --from validate --to validate-inputs
myc patch --op help
```

The write preserves your file: comments and layout survive, and only the entries that actually changed are reprinted. A rename of `:delete` to `:remove` in a 30-line manifest is a 3-line diff. Writes are atomic (temp file + rename).

`--expect-hash` is optimistic concurrency: `myc hash` the manifest, do your work, and the patch aborts with `Stale manifest: expected hash X but current hash: Y` if anything changed underneath you. The hash is over the file's own EDN content (key order and whitespace insensitive); fragment files and registered handler schemas are not part of it. `--dry-run` validates and reports the would-be new hash without writing.

### rename-cell

`--op rename-cell --from <old> --to <new>` rewrites every reference: cells, edges, dispatches, joins (both name and membership), regions, constraints, timeouts, resilience, error-groups, `:on-error` targets, `:pipeline` order, and fragment `:as` aliases and `:exits` targets. It refuses to rename `:start` (reachability is rooted there), terminals, a name that already exists (including cells contributed by fragments), and cells defined *inside* a fragment — those are renamed in the fragment file, and the error names it:

```
Cell :fetch-stats is defined by fragment :tail (fragments/fetch-render-list.edn) — rename it in the fragment file
```

Repeat `--op` to batch several edits into one validated write.

## Skills

Skills are version-matched — they ship in the library's resources and are served by the CLI you're running, so documentation can never drift from the installed version. `myc skills` lists each topic with its served size and a one-line description; fetch a topic at most once per session, its content is fixed for a given build.

Topics: `agent` (loop + rules), `manifest` (EDN syntax), `cells` (handler contract), `testing` (isolation + trace), `patterns` (branching, joins, halt/resume, fragments, constraints). Sections are fetchable individually with `--section`, e.g. `myc skills get agent --section edit-loop`.

## The agent loop

1. `myc status` — find pending or failing cells.
2. `myc brief <cell>` — get the contract: schema, resources, examples, dispatch labels. Implement the handler against the brief alone.
3. `myc test <cell>` — run it in isolation, then again with boundary inputs via `--input`.
4. `myc status` again — confirm green.
5. For structural edits, `myc refs <cell>` to see what a change touches, `myc hash`, then `myc patch --expect-hash <hash>` so a concurrent edit fails loudly instead of silently clobbering.
