package io.github.subnocte.springwiring.mcp;

/**
 * One directory {@code indexStatus} knows about, for the caller to judge how much ad hoc
 * root/ref usage has accumulated in this server's lifetime.
 *
 * @param path absolute, normalized path of the directory
 * @param kind {@link #KIND_DEFAULT}, {@link #KIND_ROOT}, or {@link #KIND_REF}
 */
public record KnownRoot(String path, String kind) {

    /** The server's startup {@code code.root}. */
    public static final String KIND_DEFAULT = "default";

    /** A plain directory supplied via the {@code root} parameter. */
    public static final String KIND_ROOT = "root";

    /** A directory materialized from a {@code ref} parameter. */
    public static final String KIND_REF = "ref";
}
