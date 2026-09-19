# Agent CLI

`myc` is the agent-facing command line: scoped reads over an EDN manifest, checked edits, and bundled skills. The manifest is the program database — every command answers a specific question instead of dumping whole files into context.

## Setup

From a mycelium checkout:

```sh
./bin/myc <command>
```

As a library dependency, invoke the entry point directly:

```sh
clojure -M -m mycelium.cli <command>
```

## Commands

```sh
myc validate <path>            structural validation; exit 0/1
myc hash <path>                canonical content hash (sha-256 over sorted EDN)
myc status <path> [--json]     per-cell implementation status; exit 3 unless all passing
myc brief <path> <cell>        self-contained implementation brief for one cell
myc briefs <path>              briefs for every cell
myc region <path> <name>       scoped brief for a :regions cluster
myc plan <path>                build order
myc paths <path>               every start-to-terminal path
myc schema <path>              data keys available at each cell
myc dot <path>                 DOT graph rendering
myc skills                     list bundled skill topics
myc skills get <topic> [--section <id>]
                               print one skill topic or section
myc patch <path> --op rename-cell --from <old> --to <new>
            [--expect-hash <hash>] [--dry-run]
                               checked manifest edit
```

`--require <ns>` (repeatable) loads handler namespaces before querying, so registered cells are visible to `status` and `schema`.

Exit codes: 0 success, 1 failure, 3 status not green, 64 usage error, 66 manifest file missing.

## The agent loop

1. `myc status` — find pending or failing cells.
2. `myc brief <cell>` — get the contract: schema, resources, examples, dispatch labels. Implement the handler against the brief alone.
3. Test the cell in isolation, including boundary inputs.
4. `myc status` again — confirm green.
5. For structural edits, `myc hash` then `myc patch --expect-hash <hash>` so a concurrent edit fails loudly instead of silently clobbering.

## Checked edits

`myc patch` applies structural operations that rewrite every reference to a cell at once — cells, edges, dispatches, joins (both name and membership), regions, constraints, timeouts, resilience, error-groups, `:on-error` targets, and `:pipeline` order. The result is re-validated with the full manifest validator before the file is written; a rejected edit leaves the file untouched.

`--expect-hash` is optimistic concurrency: hash the manifest, do your work, and the patch aborts with `Stale manifest: expected hash X but current hash: Y` if anything changed underneath you. `--dry-run` validates and reports the would-be new hash without writing.

Bundled skills (`myc skills get agent`) carry the full discipline rules: scoped reads over bulk reads, no redundant confirmation queries, manifest-wins schema disputes.

## Skills

Skills are version-matched — they ship in the library's resources and are served by the CLI you're running, so documentation can never drift from the installed version. Topics: `agent` (loop + rules), `manifest` (EDN syntax), `cells` (handler contract), `testing` (isolation + trace), `patterns` (branching, joins, halt/resume, fragments, constraints). Sections are fetchable individually with `--section`, e.g. `myc skills get agent --section edit-loop`.
