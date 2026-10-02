package com.aifashionvault.studio.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;

import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

/**
 * Deterministic planner for the constrained natural-language Studio surface
 * (C21).
 *
 * <p>It maps a narrow, fixed vocabulary onto the SAME {@link StudioAgent}
 * tool-call shape the manual Studio controls use, so text and buttons share one
 * state model and one executor:
 *
 * <pre>
 *   find &lt;text&gt;                         searchVault
 *   add &lt;item&gt; to|as|into &lt;slot&gt;        addItem
 *   replace &lt;slot&gt; with &lt;item&gt; [at &lt;n&gt;]  replaceItem
 *   remove &lt;item&gt; | remove the &lt;slot&gt;   removeItem
 *   clear &lt;slot&gt;                       removeItem (one per occupied position)
 *   explain conflict                    no call; describes the current draft
 * </pre>
 *
 * <p>Properties that matter for release:
 * <ul>
 *   <li>No text model is used. Planning is deterministic string work plus
 *       read-only lookups, so there is no spend, no prompt retention and no
 *       untrusted text reaching a provider. The planner can therefore only
 *       ever emit tool names from {@link StudioAgent}'s allowlist; anything
 *       unrecognised is refused before a call is produced.</li>
 *   <li>Planning never mutates. {@code searchVault} and {@code getStudioState}
 *       are reads; the emitted calls are executed by the caller through
 *       {@link StudioAgent#execute}. A refused or ambiguous command therefore
 *       leaves the draft exactly as it was.</li>
 *   <li>Ambiguity is surfaced, never guessed. Several matching garments yield
 *       {@link NeedsSelection} with the candidates, and the user must send an
 *       explicit item id before any mutating call is produced. Slot conflicts
 *       and already-occupied single-occupant slots yield {@link Refused} with
 *       an explanation instead of a doomed call.</li>
 * </ul>
 *
 * <p>Ownership is not re-implemented here: every lookup and every emitted call
 * carries the caller's {@link UserId}, and the tool layer remains the authority
 * that rejects cross-owner access.
 */
public final class StudioCommandPlanner {

    /** Longest accepted command; also bounds every value forwarded to the tools. */
    private static final int MAX_TEXT = 200;

    /** Outcome of planning a single command. */
    public sealed interface Plan permits Resolved, NeedsSelection, Explanation, Refused {}

    /** Safe calls ready to hand to {@link StudioAgent#execute}. */
    public record Resolved(List<StudioAgent.RawToolCall> calls) implements Plan {
        public Resolved {
            calls = List.copyOf(calls);
        }
    }

    /**
     * More than one owned item matched; the user must choose explicitly.
     *
     * <p>{@code slot} is the target slot already resolved from the command (for
     * {@code add}/{@code replace}), so a caller does not have to re-derive it from the
     * garment's category. It is {@code null} when the command carries no slot of its own
     * (a bare lookup) or the item is not in the draft.
     */
    public record NeedsSelection(String prompt, List<SelectionCandidate> candidates, StudioSlot slot)
            implements Plan {
        public NeedsSelection {
            prompt = Objects.requireNonNull(prompt);
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * One owned item offered for explicit selection. A public projection of the
     * package-private {@code VaultItemSummary} so an endpoint in another package
     * can render the choice.
     */
    public record SelectionCandidate(VaultItemId id, String label) {
        public SelectionCandidate {
            id = Objects.requireNonNull(id);
            label = Objects.requireNonNull(label);
        }
    }

    /** Read-only answer (nothing to clear, conflict description). */
    public record Explanation(String message) implements Plan {
        public Explanation {
            message = Objects.requireNonNull(message);
        }
    }

    /** The command was understood but no safe mutation could be produced. */
    public record Refused(AgentProblemCode problem, String message) implements Plan {
        public Refused {
            problem = Objects.requireNonNull(problem);
            message = Objects.requireNonNull(message);
        }
    }

    private static final Map<String, StudioSlot> SLOT_WORDS = slotWords();

    private final StudioAgentTools tools;

    public StudioCommandPlanner(StudioAgentTools tools) {
        this.tools = Objects.requireNonNull(tools);
    }

    /** Plans one command for the given owner and draft. Never mutates. */
    public Plan plan(UserId actor, StudioSessionId session, String text) {
        if (actor == null || session == null) {
            throw new IllegalArgumentException("actor and session are required");
        }
        if (text == null) {
            return refused(AgentProblemCode.MALFORMED_CALL, "Type a Studio command.");
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_TEXT) {
            return refused(
                    AgentProblemCode.MALFORMED_CALL,
                    "Commands must be between 1 and " + MAX_TEXT + " characters.");
        }
        try {
            if (startsWith(trimmed, "find ")) {
                return search(trimmed.substring(5));
            }
            if (startsWith(trimmed, "search ")) {
                return search(trimmed.substring(7));
            }
            if (startsWith(trimmed, "show me ")) {
                return search(trimmed.substring(8));
            }
            if (startsWith(trimmed, "add ")) {
                return planAdd(actor, session, trimmed.substring(4));
            }
            if (startsWith(trimmed, "replace ")) {
                return planReplace(actor, session, trimmed.substring(8));
            }
            if (startsWith(trimmed, "remove ")) {
                return planRemove(actor, session, trimmed.substring(7));
            }
            if (startsWith(trimmed, "delete ")) {
                return planRemove(actor, session, trimmed.substring(7));
            }
            if (startsWith(trimmed, "clear ")) {
                return planClear(actor, session, trimmed.substring(6));
            }
            if (isExplain(trimmed)) {
                return planExplain(actor, session);
            }
        } catch (DataAccessException e) {
            return refused(AgentProblemCode.DEPENDENCY_UNAVAILABLE, "The Studio data store is unavailable.");
        } catch (IllegalArgumentException e) {
            return refused(AgentProblemCode.MALFORMED_CALL, "That command could not be understood.");
        }
        return refused(
                AgentProblemCode.MALFORMED_CALL,
                "Unsupported command. Try: find, add, replace, remove, clear, or explain conflict.");
    }

    private Plan search(String rawQuery) {
        String query = normalizeDescriptor(rawQuery);
        if (query.isEmpty()) {
            return refused(AgentProblemCode.MALFORMED_CALL, "Say what to find, for example \"find blue shirt\".");
        }
        return new Resolved(List.of(call("searchVault", Map.of("query", query))));
    }

    private Plan planAdd(UserId actor, StudioSessionId session, String rest) {
        Split split = splitOnLastConnector(rest, " to ", " as ", " into ");
        if (split == null) {
            return refused(
                    AgentProblemCode.MALFORMED_CALL,
                    "Say what to add and where, for example \"add my blue shirt to the top\".");
        }
        StudioSlot slot = slotFor(split.tail());
        if (slot == null) {
            return refused(AgentProblemCode.MALFORMED_CALL, "Unknown slot \"" + split.tail() + "\".");
        }
        ItemPick pick = resolveItem(actor, session, split.head(), slot);
        if (pick.problem() != null) {
            return pick.problem();
        }
        StudioStateValue state = readState(actor, session);
        if (state == null) {
            return refused(AgentProblemCode.DEPENDENCY_UNAVAILABLE, "The Studio draft is unavailable.");
        }
        String occupancy = occupancyProblem(state, slot);
        if (occupancy != null) {
            return refused(AgentProblemCode.STATE_CONFLICT, occupancy);
        }
        return new Resolved(List.of(call(
                "addItem",
                Map.of("vaultItemId", pick.id().value().toString(), "slot", slot.name()))));
    }

    private Plan planReplace(UserId actor, StudioSessionId session, String rest) {
        Split split = splitOnLastConnector(rest, " with ");
        if (split == null) {
            return refused(
                    AgentProblemCode.MALFORMED_CALL,
                    "Say what to replace and with what, for example \"replace the top with my blue shirt\".");
        }
        StudioSlot slot = slotFor(split.head());
        if (slot == null) {
            return refused(AgentProblemCode.MALFORMED_CALL, "Unknown slot \"" + split.head() + "\".");
        }
        String descriptor = split.tail();
        Integer position = null;
        Split at = splitOnLastConnector(descriptor, " at ");
        if (at != null && at.tail().matches("\\d{1,4}")) {
            position = Integer.valueOf(at.tail());
            descriptor = at.head();
        }
        if (slot.allowsMultiple() && position == null) {
            return refused(
                    AgentProblemCode.MALFORMED_CALL,
                    "The " + slot.name() + " slot holds several items; add \"at <position>\".");
        }
        if (!slot.allowsMultiple() && position != null) {
            return refused(
                    AgentProblemCode.MALFORMED_CALL,
                    "The " + slot.name() + " slot holds one item; drop \"at <position>\".");
        }
        ItemPick pick = resolveItem(actor, session, descriptor, slot);
        if (pick.problem() != null) {
            return pick.problem();
        }
        StudioStateValue state = readState(actor, session);
        if (state == null) {
            return refused(AgentProblemCode.DEPENDENCY_UNAVAILABLE, "The Studio draft is unavailable.");
        }
        String occupancy = occupancyProblem(state, slot);
        if (occupancy != null) {
            return refused(AgentProblemCode.STATE_CONFLICT, occupancy);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("vaultItemId", pick.id().value().toString());
        arguments.put("slot", slot.name());
        if (position != null) {
            arguments.put("position", position);
        }
        return new Resolved(List.of(call("replaceItem", arguments)));
    }

    private Plan planRemove(UserId actor, StudioSessionId session, String rest) {
        StudioSlot slot = slotFor(rest);
        if (slot != null) {
            if (slot.allowsMultiple()) {
                return refused(
                        AgentProblemCode.MALFORMED_CALL,
                        "The " + slot.name() + " slot holds several items; name the item or use \"clear "
                                + slot.name().toLowerCase(Locale.ROOT) + "\".");
            }
            return new Resolved(List.of(call("removeItem", Map.of("slot", slot.name()))));
        }
        ItemPick pick = resolveItem(actor, session, rest, null);
        if (pick.problem() != null) {
            return pick.problem();
        }
        StudioStateValue state = readState(actor, session);
        if (state == null) {
            return refused(AgentProblemCode.DEPENDENCY_UNAVAILABLE, "The Studio draft is unavailable.");
        }
        for (StudioItemValue item : state.items()) {
            if (item.vaultItemId().equals(pick.id())) {
                Map<String, Object> arguments = new LinkedHashMap<>();
                arguments.put("slot", item.slot().name());
                if (item.slot().allowsMultiple()) {
                    arguments.put("position", item.position());
                }
                return new Resolved(List.of(call("removeItem", arguments)));
            }
        }
        return refused(AgentProblemCode.UNAUTHORIZED_OR_NOT_FOUND, "That item is not in this draft.");
    }

    private Plan planClear(UserId actor, StudioSessionId session, String rest) {
        StudioSlot slot = slotFor(rest);
        if (slot == null) {
            return refused(AgentProblemCode.MALFORMED_CALL, "Unknown slot \"" + rest + "\".");
        }
        StudioStateValue state = readState(actor, session);
        if (state == null) {
            return refused(AgentProblemCode.DEPENDENCY_UNAVAILABLE, "The Studio draft is unavailable.");
        }
        List<StudioItemValue> occupied = state.items().stream().filter(item -> item.slot() == slot).toList();
        if (occupied.isEmpty()) {
            return new Explanation("The " + slot.name() + " slot is already empty.");
        }
        List<StudioAgent.RawToolCall> calls = new ArrayList<>();
        if (slot.allowsMultiple()) {
            // Descending positions: removing a low position first would shift
            // the positions the later calls still refer to.
            occupied.stream()
                    .sorted(Comparator.comparingInt(StudioItemValue::position).reversed())
                    .forEach(item -> calls.add(call(
                            "removeItem", Map.of("slot", slot.name(), "position", item.position()))));
        } else {
            calls.add(call("removeItem", Map.of("slot", slot.name())));
        }
        return new Resolved(calls);
    }

    private Plan planExplain(UserId actor, StudioSessionId session) {
        StudioStateValue state = readState(actor, session);
        if (state == null) {
            return refused(AgentProblemCode.DEPENDENCY_UNAVAILABLE, "The Studio draft is unavailable.");
        }
        if (state.items().isEmpty()) {
            return new Explanation("This draft has no items yet, so no slot conflict is possible.");
        }
        Map<StudioSlot, Integer> counts = new LinkedHashMap<>();
        for (StudioItemValue item : state.items()) {
            counts.merge(item.slot(), 1, Integer::sum);
        }
        StringBuilder message = new StringBuilder("This draft holds ");
        boolean first = true;
        for (Map.Entry<StudioSlot, Integer> entry : counts.entrySet()) {
            if (!first) {
                message.append(", ");
            }
            message.append(entry.getKey().name()).append('=').append(entry.getValue());
            first = false;
        }
        message.append(". ");
        if (counts.containsKey(StudioSlot.FULL_BODY)) {
            message.append("A full-body item conflicts with upper- and lower-body items, so those cannot be added together.");
        } else {
            message.append("A full-body item would conflict with any upper- or lower-body item already here.");
        }
        return new Explanation(message.toString());
    }

    private StudioStateValue readState(UserId actor, StudioSessionId session) {
        ToolValue value = tools.getStudioState(actor, session);
        return value instanceof StudioStateValue state ? state : null;
    }

    private ItemPick resolveItem(UserId actor, StudioSessionId session, String rawDescriptor, StudioSlot targetSlot) {
        String descriptor = normalizeDescriptor(rawDescriptor);
        if (descriptor.isEmpty()) {
            return new ItemPick(null, refused(AgentProblemCode.MALFORMED_CALL, "Name the item, for example \"my blue shirt\"."));
        }
        VaultItemId explicit = explicitId(descriptor);
        if (explicit != null) {
            return new ItemPick(explicit, null);
        }
        ToolValue value = tools.searchVault(actor, session, descriptor, null);
        if (!(value instanceof VaultItemsValue items)) {
            return new ItemPick(null, refused(AgentProblemCode.DEPENDENCY_UNAVAILABLE, "The Vault lookup returned an unexpected result."));
        }
        List<VaultItemSummary> candidates = items.items();
        if (candidates.isEmpty()) {
            return new ItemPick(
                    null,
                    refused(AgentProblemCode.UNAUTHORIZED_OR_NOT_FOUND, "No item in your Vault matches \"" + descriptor + "\"."));
        }
        VaultItemSummary chosen = choose(candidates, descriptor);
        if (chosen != null) {
            return new ItemPick(chosen.id(), null);
        }
        return new ItemPick(
                null,
                new NeedsSelection(
                        "Several items match \"" + descriptor + "\". Send the item id to use.",
                        candidates.stream().map(StudioCommandPlanner::candidate).toList(),
                        targetSlot));
    }

    private static SelectionCandidate candidate(VaultItemSummary summary) {
        String label = summary.name() == null || summary.name().isBlank()
                ? summary.id().value().toString()
                : summary.name().strip();
        return new SelectionCandidate(summary.id(), label);
    }

    /**
     * A single candidate is unambiguous. With several, only an exact
     * case-insensitive name match resolves without asking; anything else needs
     * an explicit choice.
     */
    private static VaultItemSummary choose(List<VaultItemSummary> candidates, String descriptor) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        String needle = descriptor.toLowerCase(Locale.ROOT);
        VaultItemSummary exact = null;
        int exactCount = 0;
        for (VaultItemSummary candidate : candidates) {
            if (candidate.name() != null && candidate.name().strip().toLowerCase(Locale.ROOT).equals(needle)) {
                exact = candidate;
                exactCount++;
            }
        }
        return exactCount == 1 ? exact : null;
    }

    private static String occupancyProblem(StudioStateValue state, StudioSlot slot) {
        for (StudioItemValue item : state.items()) {
            if (item.slot().conflictsWith(slot)) {
                return slot.name() + " conflicts with the " + item.slot().name() + " item already in this draft.";
            }
        }
        if (!slot.allowsMultiple()) {
            for (StudioItemValue item : state.items()) {
                if (item.slot() == slot) {
                    return "The " + slot.name() + " slot already has an item; use \"replace\" instead.";
                }
            }
        }
        return null;
    }

    private static StudioAgent.RawToolCall call(String tool, Map<String, Object> arguments) {
        return new StudioAgent.RawToolCall(tool, arguments);
    }

    private static Refused refused(AgentProblemCode problem, String message) {
        return new Refused(problem, message);
    }

    private static boolean startsWith(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isExplain(String text) {
        return startsWith(text, "explain");
    }

    private static StudioSlot slotFor(String word) {
        return word == null ? null : SLOT_WORDS.get(normalizeDescriptor(word));
    }

    private static String normalizeDescriptor(String value) {
        String result = value == null ? "" : value.strip();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String article : List.of("the ", "my ", "a ", "an ")) {
                if (startsWith(result, article)) {
                    result = result.substring(article.length()).strip();
                    changed = true;
                }
            }
        }
        return result;
    }

    private static VaultItemId explicitId(String descriptor) {
        try {
            return new VaultItemId(UUID.fromString(descriptor.strip()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Split splitOnLastConnector(String text, String... connectors) {
        String lower = text.toLowerCase(Locale.ROOT);
        int best = -1;
        int length = 0;
        for (String connector : connectors) {
            int index = lower.lastIndexOf(connector);
            if (index > best) {
                best = index;
                length = connector.length();
            }
        }
        if (best < 0) {
            return null;
        }
        return new Split(text.substring(0, best).strip(), text.substring(best + length).strip());
    }

    private static Map<String, StudioSlot> slotWords() {
        Map<String, StudioSlot> words = new LinkedHashMap<>();
        for (String word : List.of("top", "tops", "upper", "upper body", "shirt", "shirts", "t-shirt", "tshirt",
                "tee", "blouse", "jacket", "coat", "sweater", "hoodie", "kurta", "kurti")) {
            words.put(word, StudioSlot.UPPER_BODY);
        }
        for (String word : List.of("bottom", "bottoms", "lower", "lower body", "pants", "trousers", "jeans", "shorts",
                "skirt", "leggings")) {
            words.put(word, StudioSlot.LOWER_BODY);
        }
        for (String word : List.of("feet", "foot", "shoe", "shoes", "footwear", "sneakers", "boots", "sandals")) {
            words.put(word, StudioSlot.FEET);
        }
        for (String word : List.of("full body", "full", "dress", "gown", "jumpsuit")) {
            words.put(word, StudioSlot.FULL_BODY);
        }
        for (String word : List.of("head", "hat", "cap", "headwear")) {
            words.put(word, StudioSlot.HEAD);
        }
        for (String word : List.of("bag", "bags", "handbag", "purse")) {
            words.put(word, StudioSlot.BAG);
        }
        for (String word : List.of("jewellery", "jewelry", "necklace", "earrings", "bracelet", "ring")) {
            words.put(word, StudioSlot.JEWELLERY);
        }
        for (String word : List.of("accessory", "accessories", "belt", "watch", "scarf", "sunglasses")) {
            words.put(word, StudioSlot.ACCESSORY);
        }
        return Map.copyOf(words);
    }

    private record Split(String head, String tail) {}

    private record ItemPick(VaultItemId id, Plan problem) {}
}
