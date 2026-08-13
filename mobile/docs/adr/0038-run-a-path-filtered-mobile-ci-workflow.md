# Run a path-filtered mobile CI workflow

GitHub Actions will run a dedicated mobile workflow for relevant `mobile/**` changes, covering shared and Android unit tests plus an Android build, while changes to mobile protocol DTOs or their Engine endpoints also trigger the live contract suite. The slower Android instrumentation tracer runs as a separate job, iOS build and test jobs begin only with the iOS phase, and mobile-only changes do not fan out through the entire existing Rotki CI matrix.
