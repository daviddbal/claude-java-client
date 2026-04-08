package net.balsoftware.claude;

/**
 * Named constants for commonly used Claude models.
 *
 * Use these in code instead of raw strings. The env var CLAUDE_MODEL
 * always takes precedence at runtime if you need to override without recompiling.
 *
 * Pricing tiers (fastest/cheapest → most capable/expensive):
 *   HAIKU → SONNET → OPUS
 */
public final class ClaudeModel {

    public static final String HAIKU  = "claude-haiku-4-5-20251001";
    public static final String SONNET = "claude-sonnet-4-6";
    public static final String OPUS   = "claude-opus-4-6";

    /** The default model used when CLAUDE_MODEL env var is not set. */
    public static final String DEFAULT = HAIKU;

    private ClaudeModel() {}
}