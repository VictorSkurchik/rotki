# Companion Protocol v1 contract assets

This directory is the machine-readable source of truth for the Portfolio Companion
Protocol v1 bootstrap and authorization control plane.

- `vocabulary.json` owns stable protocol tokens shared by the Engine, Kotlin, and Swift.
- `generated_names.json` is the checked output for Python, Kotlin, and Swift identifiers.
- `p0_1_cases.json` owns the route, recovery-state, error, and threat-test matrices.
- `golden_vectors.json` owns exact wire examples and the Device Key proof vector.

The narrative contract remains in `mobile/docs/protocol.md`. Phase P0.1 validates these
assets without implementing production endpoints. Runtime Engine and Client tests will
consume the same files as their corresponding delivery slices are implemented.

Run the focused validation with:

```bash
uv run pytest -q --confcutdir=rotkehlchen/tests/api/companion \
  rotkehlchen/tests/api/companion/test_protocol_spec.py
```

Regenerate or check cross-language identifiers with:

```bash
uv run python tools/scripts/generate_companion_protocol_vocabulary.py --write
uv run python tools/scripts/generate_companion_protocol_vocabulary.py --check
```

JSON is UTF-8, uses two-space indentation, and ends with one newline. Duplicate object
members are forbidden even when a normal JSON parser would keep only the last value.
