package io.github.subnocte.springwiring.mcp;

/**
 * Self-reported identity of a materialized git ref, included in a tool's response whenever
 * the {@code ref} parameter was supplied: which ref was asked for, the immutable commit SHA
 * it resolves to right now, and that commit's timestamp. This project never fetches on the
 * caller's behalf, so surfacing the exact commit is what lets a caller notice a stale local
 * clone (e.g. {@code origin/develop} not being where they expected).
 */
public record ResolvedCommit(String ref, String sha, String committedAt) {
}
