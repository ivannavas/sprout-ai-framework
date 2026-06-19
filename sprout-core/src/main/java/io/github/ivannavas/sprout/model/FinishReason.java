package io.github.ivannavas.sprout.model;

/** Why a model stopped generating: a natural stop, a tool request, the token cap, or an error. */
public enum FinishReason {
    STOP, TOOL_CALLS, MAX_TOKENS, ERROR
}
