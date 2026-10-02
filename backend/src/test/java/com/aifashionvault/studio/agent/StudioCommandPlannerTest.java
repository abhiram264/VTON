package com.aifashionvault.studio.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

class StudioCommandPlannerTest {

  /** Mirrors StudioAgent's private allowlist; a call outside it must never be produced. */
  private static final Set<String> ALLOWLIST = Set.of(
      "searchVault", "getVaultItem", "listCollection", "addItem", "removeItem", "replaceItem",
      "setAvatar", "getStudioState", "generateLook", "saveLook");

  private final UserId actor = new UserId(UUID.randomUUID());
  private final StudioSessionId session = new StudioSessionId(UUID.randomUUID());
  private final FakeTools tools = new FakeTools();
  private final StudioCommandPlanner planner = new StudioCommandPlanner(tools);
  private final StudioAgent agent = new StudioAgent(tools);

  private VaultItemSummary summary(String name) {
    return new VaultItemSummary(new VaultItemId(UUID.randomUUID()), name, "Brand", "TOP", null, "Blue", "Solid");
  }

  private StudioItemValue draftItem(VaultItemId vaultItemId, StudioSlot slot, int position) {
    return new StudioItemValue(new StudioItemId(UUID.randomUUID()), slot, vaultItemId, position);
  }

  private void givenDraft(StudioItemValue... items) {
    tools.state = new StudioStateValue(session, new AvatarProfileId(UUID.randomUUID()), List.of(items));
  }

  private List<StudioAgent.RawToolCall> resolved(StudioCommandPlanner.Plan plan) {
    assertThat(plan).isInstanceOf(StudioCommandPlanner.Resolved.class);
    return ((StudioCommandPlanner.Resolved) plan).calls();
  }

  private StudioCommandPlanner.Refused refused(StudioCommandPlanner.Plan plan) {
    assertThat(plan).isInstanceOf(StudioCommandPlanner.Refused.class);
    return (StudioCommandPlanner.Refused) plan;
  }

  @Test
  void findEmitsOnlyASearchCallAndRunsThroughTheSharedExecutor() {
    List<StudioAgent.RawToolCall> calls = resolved(planner.plan(actor, session, "find my blue shirt"));

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).tool()).isEqualTo("searchVault");
    assertThat(calls.get(0).arguments()).containsEntry("query", "blue shirt");

    StudioAgent.AgentExecution execution = agent.execute(actor, session, calls);
    assertThat(execution.completed()).isTrue();
    assertThat(tools.invocations).containsExactly("searchVault");
    assertThat(tools.lastQuery).isEqualTo("blue shirt");
  }

  @Test
  void addResolvesASingleMatchIntoTheNamedSlot() {
    VaultItemSummary shirt = summary("Blue Shirt");
    tools.matches = List.of(shirt);

    List<StudioAgent.RawToolCall> calls = resolved(planner.plan(actor, session, "add my blue shirt to the top"));

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).tool()).isEqualTo("addItem");
    assertThat(calls.get(0).arguments())
        .containsEntry("vaultItemId", shirt.id().value().toString())
        .containsEntry("slot", StudioSlot.UPPER_BODY.name());
    assertThat(agent.execute(actor, session, calls).completed()).isTrue();
  }

  @Test
  void duplicateMatchesRequireExplicitSelectionAndMutateNothing() {
    tools.matches = List.of(summary("Blue Shirt A"), summary("Blue Shirt B"));

    StudioCommandPlanner.Plan plan = planner.plan(actor, session, "add my blue shirt to the top");

    assertThat(plan).isInstanceOf(StudioCommandPlanner.NeedsSelection.class);
    StudioCommandPlanner.NeedsSelection selection = (StudioCommandPlanner.NeedsSelection) plan;
    assertThat(selection.candidates()).extracting(StudioCommandPlanner.SelectionCandidate::label)
        .containsExactly("Blue Shirt A", "Blue Shirt B");
    assertThat(selection.prompt()).contains("item id");
    // The command's own target slot is carried through, so the client does not
    // have to re-derive it from the garment's category.
    assertThat(selection.slot()).isEqualTo(StudioSlot.UPPER_BODY);
    // Only the read-only lookup ran: the draft is untouched.
    assertThat(tools.invocations).containsExactly("searchVault");
    assertThat(tools.mutations).isEmpty();
  }

  @Test
  void ambiguityCarriesTheTargetSlotFromReplaceAndNoneFromABareLookup() {
    tools.matches = List.of(summary("Blue Shirt A"), summary("Blue Shirt B"));

    StudioCommandPlanner.NeedsSelection fromReplace = (StudioCommandPlanner.NeedsSelection) planner
        .plan(actor, session, "replace the bottom with my blue shirt");
    assertThat(fromReplace.slot()).isEqualTo(StudioSlot.LOWER_BODY);

    StudioCommandPlanner.NeedsSelection fromRemove = (StudioCommandPlanner.NeedsSelection) planner
        .plan(actor, session, "remove my blue shirt");
    assertThat(fromRemove.slot()).isNull();

    assertThat(tools.mutations).isEmpty();
  }

  @Test
  void anExplicitItemIdResolvesWithoutSearching() {
    String id = UUID.randomUUID().toString();

    List<StudioAgent.RawToolCall> calls = resolved(planner.plan(actor, session, "add " + id + " to the top"));

    assertThat(calls.get(0).arguments()).containsEntry("vaultItemId", id);
    assertThat(tools.invocations).doesNotContain("searchVault");
  }

  @Test
  void anExactNameMatchResolvesAmongDuplicates() {
    VaultItemSummary exact = summary("Blue Shirt");
    tools.matches = List.of(exact, summary("Blue Shirt Deluxe"));

    List<StudioAgent.RawToolCall> calls = resolved(planner.plan(actor, session, "add blue shirt to the top"));

    assertThat(calls.get(0).arguments()).containsEntry("vaultItemId", exact.id().value().toString());
  }

  @Test
  void replaceUsesTheNamedSlotWithoutAPositionForSingleOccupantSlots() {
    VaultItemSummary shirt = summary("Blue Shirt");
    tools.matches = List.of(shirt);

    List<StudioAgent.RawToolCall> calls = resolved(planner.plan(actor, session, "replace the top with my blue shirt"));

    assertThat(calls.get(0).tool()).isEqualTo("replaceItem");
    assertThat(calls.get(0).arguments())
        .containsEntry("slot", StudioSlot.UPPER_BODY.name())
        .doesNotContainKey("position");
  }

  @Test
  void aConflictingSlotIsRefusedWithoutProducingACall() {
    givenDraft(draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.UPPER_BODY, 0));
    tools.matches = List.of(summary("Red Dress"));

    StudioCommandPlanner.Refused refusal = refused(planner.plan(actor, session, "add a dress to full body"));

    assertThat(refusal.problem()).isEqualTo(AgentProblemCode.STATE_CONFLICT);
    assertThat(refusal.message()).contains("FULL_BODY").contains("UPPER_BODY");
    assertThat(tools.mutations).isEmpty();
  }

  @Test
  void anOccupiedSingleSlotRefusesAddAndPointsAtReplace() {
    givenDraft(draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.UPPER_BODY, 0));
    tools.matches = List.of(summary("Blue Shirt"));

    StudioCommandPlanner.Refused refusal = refused(planner.plan(actor, session, "add my blue shirt to the top"));

    assertThat(refusal.problem()).isEqualTo(AgentProblemCode.STATE_CONFLICT);
    assertThat(refusal.message()).contains("replace");
    assertThat(tools.mutations).isEmpty();
  }

  @Test
  void removeByNameLocatesTheItemsSlotInTheDraft() {
    VaultItemId id = new VaultItemId(UUID.randomUUID());
    givenDraft(draftItem(id, StudioSlot.FEET, 0));
    tools.matches = List.of(new VaultItemSummary(id, "White Sneakers", null, "FOOTWEAR", null, "White", null));

    List<StudioAgent.RawToolCall> calls = resolved(planner.plan(actor, session, "remove my white sneakers"));

    assertThat(calls.get(0).tool()).isEqualTo("removeItem");
    assertThat(calls.get(0).arguments())
        .containsEntry("slot", StudioSlot.FEET.name())
        .doesNotContainKey("position");
  }

  @Test
  void removeRefusesAnItemThatIsNotInTheDraft() {
    tools.matches = List.of(summary("Blue Shirt"));

    StudioCommandPlanner.Refused refusal = refused(planner.plan(actor, session, "remove my blue shirt"));

    assertThat(refusal.problem()).isEqualTo(AgentProblemCode.UNAUTHORIZED_OR_NOT_FOUND);
    assertThat(tools.mutations).isEmpty();
  }

  @Test
  void removeBySlotNameRefusesMultiOccupantSlots() {
    StudioCommandPlanner.Refused refusal = refused(planner.plan(actor, session, "remove jewellery"));

    assertThat(refusal.problem()).isEqualTo(AgentProblemCode.MALFORMED_CALL);
    assertThat(refusal.message()).contains("clear");
  }

  @Test
  void clearEmitsOneRemovalForASingleOccupantSlot() {
    givenDraft(draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.FEET, 0));

    List<StudioAgent.RawToolCall> calls = resolved(planner.plan(actor, session, "clear the feet"));

    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).arguments())
        .containsEntry("slot", StudioSlot.FEET.name())
        .doesNotContainKey("position");
  }

  @Test
  void noPlanEverEmitsMoreThanOneMutatingCall() {
    // The NL route reports a failed execution as "nothing was changed", which is
    // only true if a plan cannot apply partially. A slot-only removal now clears a
    // multi-occupant slot in ONE call, so every plan is either a single read or a
    // single mutation — never a sequence that can stop half-applied.
    VaultItemSummary shirt = summary("Blue Shirt");
    VaultItemId draftId = new VaultItemId(UUID.randomUUID());

    tools.matches = List.of(shirt);
    List<String> commands = List.of(
        "find blue shirt",
        "add my blue shirt to the top",
        "replace the top with my blue shirt",
        "remove my blue shirt",
        "explain conflict");

    for (String command : commands) {
      givenDraft(
          draftItem(draftId, StudioSlot.JEWELLERY, 0),
          draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.JEWELLERY, 1));
      tools.matches = List.of(shirt);
      StudioCommandPlanner.Plan plan = planner.plan(actor, session, command);
      if (plan instanceof StudioCommandPlanner.Resolved resolved) {
        long mutating = resolved.calls().stream()
            .filter(call -> !call.tool().equals("searchVault") && !call.tool().equals("getStudioState"))
            .count();
        assertThat(mutating).as("mutating calls for %s", command).isLessThanOrEqualTo(1);
      }
    }

    // And the multi-occupant clear specifically is exactly one call.
    givenDraft(
        draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.JEWELLERY, 0),
        draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.JEWELLERY, 1));
    assertThat(resolved(planner.plan(actor, session, "clear jewellery"))).hasSize(1);
  }

  @Test
  void aSlotOnlyRemovalIsAcceptedForAMultiOccupantSlotAndClearsIt() {
    // Contract the planner now relies on: removeItem with no position means
    // "clear the slot", matching the tool adapter's own semantics.
    VaultItemId item = new VaultItemId(UUID.randomUUID());
    givenDraft(draftItem(item, StudioSlot.JEWELLERY, 0), draftItem(item, StudioSlot.JEWELLERY, 1));

    List<StudioAgent.RawToolCall> calls = resolved(planner.plan(actor, session, "clear jewellery"));
    StudioAgent.AgentExecution execution = agent.execute(actor, session, calls);

    assertThat(execution.completed()).isTrue();
    assertThat(execution.results().get(0).problem()).isNull();
    assertThat(tools.lastRemovalSlot).isEqualTo(StudioSlot.JEWELLERY);
    assertThat(tools.lastRemovalPosition).isNull();
  }

  @Test
  void clearEmitsOneSlotOnlyRemovalForASingleOccupantSlot() {
    givenDraft(
        draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.JEWELLERY, 0),
        draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.JEWELLERY, 1));

    List<StudioAgent.RawToolCall> calls = resolved(planner.plan(actor, session, "clear jewellery"));

    // ONE call, not one per position: a single call cannot partially apply, so a
    // mid-sequence store failure can never delete some garments and then claim
    // nothing changed.
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).tool()).isEqualTo("removeItem");
    assertThat(calls.get(0).arguments())
        .containsEntry("slot", StudioSlot.JEWELLERY.name())
        .doesNotContainKey("position");
  }

  @Test
  void clearingAnEmptySlotExplainsRatherThanCallingATool() {
    givenDraft();

    StudioCommandPlanner.Plan plan = planner.plan(actor, session, "clear the feet");

    assertThat(plan).isInstanceOf(StudioCommandPlanner.Explanation.class);
    assertThat(((StudioCommandPlanner.Explanation) plan).message()).contains("already empty");
    assertThat(tools.mutations).isEmpty();
  }

  @Test
  void explainConflictDescribesTheCurrentDraft() {
    givenDraft(
        draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.UPPER_BODY, 0),
        draftItem(new VaultItemId(UUID.randomUUID()), StudioSlot.FEET, 0));

    StudioCommandPlanner.Plan plan = planner.plan(actor, session, "explain conflict");

    assertThat(plan).isInstanceOf(StudioCommandPlanner.Explanation.class);
    String message = ((StudioCommandPlanner.Explanation) plan).message();
    assertThat(message).contains("UPPER_BODY=1").contains("FEET=1").contains("conflict");
  }

  @Test
  void injectedOrUnsupportedTextNeverProducesACall() {
    for (String text : List.of(
        "drop table vault_items",
        "'; DROP TABLE vault_items; --",
        "set avatar to http://evil.example/steal",
        "generate a look for me now",
        "delete everything",
        "   ")) {
      StudioCommandPlanner.Plan plan = planner.plan(actor, session, text);
      assertThat(plan).as("plan for %s", text).isInstanceOf(StudioCommandPlanner.Refused.class);
    }
    assertThat(tools.mutations).isEmpty();
  }

  @Test
  void everyProducedCallIsInsideTheAllowlistAndAcceptedByTheExecutor() {
    VaultItemSummary shirt = summary("Blue Shirt");
    VaultItemId draftId = new VaultItemId(UUID.randomUUID());
    List<StudioCommandPlanner.Plan> plans = new ArrayList<>();
    plans.add(planner.plan(actor, session, "find blue shirt"));

    tools.matches = List.of(shirt);
    givenDraft();
    plans.add(planner.plan(actor, session, "add my blue shirt to the top"));
    plans.add(planner.plan(actor, session, "replace the top with my blue shirt"));

    givenDraft(draftItem(draftId, StudioSlot.FEET, 0));
    plans.add(planner.plan(actor, session, "clear the feet"));

    for (StudioCommandPlanner.Plan plan : plans) {
      for (StudioAgent.RawToolCall call : resolved(plan)) {
        assertThat(ALLOWLIST).contains(call.tool());
        StudioAgent.AgentExecution execution = agent.execute(actor, session, List.of(call));
        assertThat(execution.completed()).as("execution of %s", call.tool()).isTrue();
        assertThat(execution.results().get(0).problem()).isNull();
      }
    }
  }

  @Test
  void aDependencyFailureIsReportedInsteadOfThrown() {
    tools.stateFailure = new org.springframework.dao.DataAccessResourceFailureException("database unavailable");

    StudioCommandPlanner.Refused refusal = refused(planner.plan(actor, session, "clear the feet"));

    assertThat(refusal.problem()).isEqualTo(AgentProblemCode.DEPENDENCY_UNAVAILABLE);
  }

  @Test
  void planningRequiresAnOwnerAndADraft() {
    assertThatThrownBy(() -> planner.plan(null, session, "find blue shirt"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> planner.plan(actor, null, "find blue shirt"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static class FakeTools implements StudioAgentTools {
    List<String> invocations = new ArrayList<>();
    List<String> mutations = new ArrayList<>();
    List<VaultItemSummary> matches = List.of();
    StudioStateValue state = new StudioStateValue(null, null, List.of());
    String lastQuery;
    String lastCategory;
    StudioSlot lastRemovalSlot;
    Integer lastRemovalPosition;
    RuntimeException stateFailure;

    public ToolValue searchVault(UserId a, StudioSessionId s, String query, String category) {
      invocations.add("searchVault");
      lastQuery = query;
      lastCategory = category;
      return new VaultItemsValue(matches);
    }

    public ToolValue getVaultItem(UserId a, StudioSessionId s, VaultItemId i) {
      invocations.add("getVaultItem");
      return new VaultItemValue(matches.isEmpty() ? null : matches.get(0));
    }

    public ToolValue listCollection(UserId a, StudioSessionId s, CollectionId i) {
      invocations.add("listCollection");
      return new CollectionValue(i, "c", List.of());
    }

    public ToolValue addItem(UserId a, StudioSessionId s, VaultItemId i, StudioSlot slot, int p) {
      invocations.add("addItem");
      mutations.add("addItem");
      return new StudioStateValue(s, null, List.of());
    }

    public ToolValue removeItem(UserId a, StudioSessionId s, StudioSlot slot, Integer p) {
      invocations.add("removeItem");
      mutations.add("removeItem");
      lastRemovalSlot = slot;
      lastRemovalPosition = p;
      return new StudioStateValue(s, null, List.of());
    }

    public ToolValue replaceItem(UserId a, StudioSessionId s, VaultItemId i, StudioSlot slot, Integer p) {
      invocations.add("replaceItem");
      mutations.add("replaceItem");
      return new StudioStateValue(s, null, List.of());
    }

    public ToolValue setAvatar(UserId a, StudioSessionId s, AvatarProfileId i) {
      invocations.add("setAvatar");
      mutations.add("setAvatar");
      return new StudioStateValue(s, i, List.of());
    }

    public ToolValue getStudioState(UserId a, StudioSessionId s) {
      invocations.add("getStudioState");
      if (stateFailure != null) {
        throw stateFailure;
      }
      return state;
    }

    public ToolValue generateLook(UserId a, StudioSessionId s, String k) {
      invocations.add("generateLook");
      mutations.add("generateLook");
      return new GenerationValue(new GenerationJobId(UUID.randomUUID()), "ACCEPTED");
    }

    public ToolValue saveLook(UserId a, StudioSessionId s, GenerationJobId i) {
      invocations.add("saveLook");
      mutations.add("saveLook");
      return new SavedLookValue(null);
    }
  }
}
