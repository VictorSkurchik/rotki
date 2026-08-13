# Context Map

## Modeled Contexts

- [Portfolio Companion](./mobile/CONTEXT.md) — provides native mobile portfolio viewing
  and Refresh operations against a self-hosted Rotki Engine.

The existing Engine (`rotkehlchen/`) and Full Client (`frontend/app/`) predate this context
map and retain their existing project documentation. Only the Portfolio Companion has a
dedicated domain glossary here.

## Relationships

- **Portfolio Companion → Engine**: communicates exclusively through the versioned Engine
  Protocol; the Engine owns portfolio data, external-source access, and financial behavior.
- **Full Client → Portfolio Companion**: authorizes Pairing and Device Session management
  and remains responsible for Profile unlock, administration, configuration, accounting,
  export, and financial actions.
- **Portfolio Companion documentation**: context-specific ADRs and execution documents
  live under `mobile/docs/`; the repository's existing root `docs/` tree remains reserved
  for Rotki's established Sphinx and core design documentation.
