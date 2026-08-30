package com.aifashionvault.studio.agent;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.aifashionvault.shared.*;

class StudioAgentTest {
  private final UserId actor = new UserId(UUID.randomUUID());
  private final StudioSessionId session = new StudioSessionId(UUID.randomUUID());
  private final RecordingTools tools = new RecordingTools();
  private final StudioAgent agent = new StudioAgent(tools);
  private StudioAgent.RawToolCall call(String name, Map<String,Object> args) { return new StudioAgent.RawToolCall(name,args); }
  @Test void dispatchesAllToolsWithTypedArguments() {
    String id=UUID.randomUUID().toString();
    List<StudioAgent.RawToolCall> calls=List.of(
      call("searchVault",Map.of("query"," white Nike shoes ","category"," FOOTWEAR ")),
      call("getVaultItem",Map.of("vaultItemId",id)), call("listCollection",Map.of("collectionId",id)),
      call("addItem",Map.of("vaultItemId",id,"slot","HEAD")), call("removeItem",Map.of("slot","HEAD")),
      call("replaceItem",Map.of("vaultItemId",id,"slot","HEAD")), call("setAvatar",Map.of("avatarProfileId",id)),
      call("getStudioState",Map.of()), call("generateLook",Map.of("idempotencyKey"," retry-1 ")), call("saveLook",Map.of("generationJobId",id)));
    StudioAgent.AgentExecution result=agent.execute(actor,session,calls);
    assertThat(result.completed()).isTrue(); assertThat(result.results()).hasSize(10).allSatisfy(r -> assertThat(r.status()).isEqualTo(StudioAgent.ToolStatus.OK));
    assertThat(tools.invocations).containsExactly("searchVault","getVaultItem","listCollection","addItem","removeItem","replaceItem","setAvatar","getStudioState","generateLook","saveLook");
    assertThat(tools.query).isEqualTo("white Nike shoes"); assertThat(tools.category).isEqualTo("FOOTWEAR"); assertThat(tools.key).isEqualTo("retry-1");
  }
  @Test void rejectsMalformedAndStopsBeforeLaterCall() {
    StudioAgent.AgentExecution result=agent.execute(actor,session,List.of(call("getStudioState",Map.of()),call("removeItem",Map.of("slot","JEWELLERY")),call("getStudioState",Map.of())));
    assertThat(result.completed()).isFalse(); assertThat(result.results()).extracting(StudioAgent.ToolCallResult::status).containsExactly(StudioAgent.ToolStatus.OK,StudioAgent.ToolStatus.REJECTED);
    assertThat(result.results().get(1).problem()).isEqualTo(AgentProblemCode.MALFORMED_CALL); assertThat(tools.invocations).containsExactly("getStudioState");
  }
  @Test void rejectsUnknownAndOversizedBatch() {
    var unknown=agent.execute(actor,session,List.of(call("DROP_TABLE",Map.of())));
    assertThat(unknown.results().getFirst().problem()).isEqualTo(AgentProblemCode.DISALLOWED_TOOL); assertThat(tools.invocations).isEmpty();
    var many=agent.execute(actor,session,List.copyOf(Collections.nCopies(11,call("getStudioState",Map.of()))));
    assertThat(many.completed()).isFalse(); assertThat(many.results().getFirst().problem()).isEqualTo(AgentProblemCode.MALFORMED_CALL);
  }
  @Test void mapsDependencyFailuresWithoutRelabellingProgrammerDefects() {
    tools.failure = new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
    var dependency = agent.execute(actor, session, List.of(call("getStudioState", Map.of())));
    assertThat(dependency.results().getFirst().problem()).isEqualTo(AgentProblemCode.DEPENDENCY_UNAVAILABLE);
    tools.failure = new NullPointerException("programmer defect");
    assertThatThrownBy(() -> agent.execute(actor, session, List.of(call("getStudioState", Map.of())))).isInstanceOf(NullPointerException.class);
  }
  @Test void rejectsPositionForSingleOccupantRemovalAndReplacement() {
    String id = UUID.randomUUID().toString();
    var removal = agent.execute(actor, session, List.of(call("removeItem", Map.of("slot", "HEAD", "position", 0))));
    assertThat(removal.results().getFirst().problem()).isEqualTo(AgentProblemCode.MALFORMED_CALL);
    var replacement = agent.execute(actor, session, List.of(call("replaceItem", Map.of("vaultItemId", id, "slot", "HEAD", "position", 0))));
    assertThat(replacement.results().getFirst().problem()).isEqualTo(AgentProblemCode.MALFORMED_CALL);
    assertThat(tools.invocations).isEmpty();
  }
  static class RecordingTools implements StudioAgentTools {
    List<String> invocations=new ArrayList<>(); String query,category,key; RuntimeException failure;
    private ToolValue ok(String n){invocations.add(n);return new VaultItemsValue(List.of());}
    public ToolValue searchVault(UserId a,StudioSessionId s,String q,String c){query=q;category=c;return ok("searchVault");}
    public ToolValue getVaultItem(UserId a,StudioSessionId s,VaultItemId i){return ok("getVaultItem");}
    public ToolValue listCollection(UserId a,StudioSessionId s,CollectionId i){return ok("listCollection");}
    public ToolValue addItem(UserId a,StudioSessionId s,VaultItemId i,StudioSlot x,int p){return ok("addItem");}
    public ToolValue removeItem(UserId a,StudioSessionId s,StudioSlot x,Integer p){return ok("removeItem");}
    public ToolValue replaceItem(UserId a,StudioSessionId s,VaultItemId i,StudioSlot x,Integer p){return ok("replaceItem");}
    public ToolValue setAvatar(UserId a,StudioSessionId s,AvatarProfileId i){return ok("setAvatar");}
    public ToolValue getStudioState(UserId a,StudioSessionId s){if (failure != null) throw failure; return ok("getStudioState");}
    public ToolValue generateLook(UserId a,StudioSessionId s,String k){key=k;return ok("generateLook");}
    public ToolValue saveLook(UserId a,StudioSessionId s,GenerationJobId i){return ok("saveLook");}
  }
}
