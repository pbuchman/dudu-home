package com.pbuchman.duduhome.automation;

/** Process-bound identity captured when rendering a cancellation control. */
public record AttemptToken(String session, long id) { }
