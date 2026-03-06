# Guestbook

A simple guestbook application built with Mycelium. Users can leave messages and view messages left by others.

This is the companion project for the [Your First Application](https://mycelium-clj.github.io/docs/guestbook.html) tutorial.

## Getting Started

Start a REPL:

    clojure -M:dev

Then from the REPL:

```clojure
(go)         ;; start system
(reset)      ;; reload code + restart
(halt)       ;; stop system
```

Visit [http://localhost:3000](http://localhost:3000).

## Running Tests

    clojure -M:test

## Project Structure

```
src/clj/yourname/guestbook/
  core.clj           — App lifecycle (Integrant)
  config.clj         — Configuration loading
  db.clj             — Database component (SQLite)
  web/
    handler.clj      — Ring handler setup
    middleware/
      core.clj       — Base middleware
    routes/
      pages.clj      — Page routes with workflow handlers
  cells/
    home.clj         — Default home page cells
    guestbook.clj    — Guestbook cells (load messages, render page)
  workflows/
    home.clj         — Home page workflow
    guestbook.clj    — Guestbook workflow (pipeline: load → render)
resources/
  system.edn         — System configuration (Aero + Integrant)
  html/              — Selmer HTML templates
test/clj/            — Tests
```

## Key Concepts

- **Cells** define pure functions with Malli schemas (see `cells/guestbook.clj`)
- **Workflows** compose cells into pipelines (see `workflows/guestbook.clj`)
- **Resources** (like the database) are injected into cells via `:requires`
- Workflows are pre-compiled at load time and wired to routes via `mw/workflow-handler`

## Learn More

- [Mycelium](https://github.com/mycelium-clj/mycelium) — Schema-enforced workflow framework
- [Kit](https://kit-clj.github.io/) — Modular web framework for Clojure
- [Integrant](https://github.com/weavejester/integrant) — Dependency injection
- [Reitit](https://github.com/metosin/reitit) — Data-driven routing
- [Selmer](https://github.com/yogthos/Selmer) — Django-style templating
