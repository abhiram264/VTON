/**
 * The constrained natural-language Studio agent: a deterministic planner over a
 * narrow vocabulary, the allowlisted tool-call executor, and the tool port the
 * composition layer implements.
 *
 * <p>Exposed as the named interface {@code agent} so the composition layer
 * ({@code platform}) can mount the natural-language Studio route without gaining
 * access to the rest of the {@code studio} module's internals.</p>
 */
@org.springframework.modulith.NamedInterface("agent")
package com.aifashionvault.studio.agent;
