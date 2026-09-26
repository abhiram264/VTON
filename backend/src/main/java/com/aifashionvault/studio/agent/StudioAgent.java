package com.aifashionvault.studio.agent;

import org.springframework.dao.DataAccessException;
import com.aifashionvault.shared.*;
import java.util.*;

public final class StudioAgent {
    public record RawToolCall(String tool, Map<String,Object> arguments) {
        public RawToolCall { if (tool == null || arguments == null) throw new IllegalArgumentException(); arguments = Collections.unmodifiableMap(new HashMap<>(arguments)); }
    }
    public enum ToolStatus { OK, REJECTED }
    public record ToolCallResult(int index, String tool, ToolStatus status, ToolValue value, AgentProblemCode problem) {}
    public record AgentExecution(List<ToolCallResult> results, boolean completed) { public AgentExecution { results = List.copyOf(results); } }
    public static final class AgentApplicationException extends RuntimeException {
        private final AgentProblemCode code;
        public AgentApplicationException(AgentProblemCode code) { this.code = Objects.requireNonNull(code); }
        public AgentProblemCode code() { return code; }
    }
    private final StudioAgentTools tools;
    private static final Set<String> ALLOWED = Set.of("searchVault","getVaultItem","listCollection","addItem","removeItem","replaceItem","setAvatar","getStudioState","generateLook","saveLook");
    public StudioAgent(StudioAgentTools tools) { this.tools = Objects.requireNonNull(tools); }
    public AgentExecution execute(UserId actor, StudioSessionId session, List<RawToolCall> calls) {
        if (actor == null || session == null || calls == null) throw new IllegalArgumentException();
        if (calls.size() > 10) return new AgentExecution(List.of(reject(0, null, AgentProblemCode.MALFORMED_CALL)), false);
        List<ToolCallResult> out = new ArrayList<>();
        for (int i=0; i<calls.size(); i++) {
            RawToolCall c = calls.get(i);
            if (c == null) {
                out.add(reject(i, null, AgentProblemCode.MALFORMED_CALL));
                return new AgentExecution(out, false);
            }
            AgentProblemCode error = null; ToolValue value = null;
            if (!ALLOWED.contains(c.tool())) error = AgentProblemCode.DISALLOWED_TOOL;
            else try { value = dispatch(actor, session, c); if (value == null) error = AgentProblemCode.MALFORMED_CALL; }
            catch (AgentApplicationException e) { error = e.code(); }
            catch (DataAccessException e) { error = AgentProblemCode.DEPENDENCY_UNAVAILABLE; }
            catch (IllegalArgumentException e) { error = AgentProblemCode.MALFORMED_CALL; }
            catch (RuntimeException e) { throw e; }
            if (error != null) { out.add(reject(i, c.tool(), error)); return new AgentExecution(out, false); }
            out.add(new ToolCallResult(i,c.tool(),ToolStatus.OK,value,null));
        }
        return new AgentExecution(out, true);
    }
    private ToolCallResult reject(int i, String tool, AgentProblemCode p) { return new ToolCallResult(i,tool,ToolStatus.REJECTED,null,p); }
    private ToolValue dispatch(UserId a, StudioSessionId s, RawToolCall c) {
        Map<String,Object> m=c.arguments();
        return switch(c.tool()) {
            case "searchVault" -> { schema(m, Set.of("query","category"), Set.of("query")); yield tools.searchVault(a,s,text(m,"query",200), optionalText(m,"category",64)); }
            case "getVaultItem" -> { schema(m, Set.of("vaultItemId"), Set.of("vaultItemId")); yield tools.getVaultItem(a,s,new VaultItemId(uuid(m,"vaultItemId"))); }
            case "listCollection" -> { schema(m, Set.of("collectionId"), Set.of("collectionId")); yield tools.listCollection(a,s,new CollectionId(uuid(m,"collectionId"))); }
            case "addItem" -> { schema(m, Set.of("vaultItemId","slot","position"), Set.of("vaultItemId","slot")); yield tools.addItem(a,s,new VaultItemId(uuid(m,"vaultItemId")),slot(m),requiredPosition(m)); }
            case "removeItem" -> { schema(m, Set.of("slot","position"), Set.of("slot")); yield tools.removeItem(a,s,slot(m),optionalPosition(m)); }
            case "replaceItem" -> { schema(m, Set.of("vaultItemId","slot","position"), Set.of("vaultItemId","slot")); yield tools.replaceItem(a,s,new VaultItemId(uuid(m,"vaultItemId")),slot(m),optionalPosition(m)); }
            case "setAvatar" -> { schema(m, Set.of("avatarProfileId"), Set.of("avatarProfileId")); yield tools.setAvatar(a,s,new AvatarProfileId(uuid(m,"avatarProfileId"))); }
            case "getStudioState" -> { exact(m); yield tools.getStudioState(a,s); }
            case "generateLook" -> { schema(m, Set.of("idempotencyKey"), Set.of("idempotencyKey")); yield tools.generateLook(a,s,text(m,"idempotencyKey",128)); }
            case "saveLook" -> { schema(m, Set.of("generationJobId"), Set.of("generationJobId")); yield tools.saveLook(a,s,new GenerationJobId(uuid(m,"generationJobId"))); }
            default -> throw new IllegalStateException();
        };
    }
    private static void schema(Map<String,Object> m, Set<String> allowed, Set<String> required) { if(!allowed.containsAll(m.keySet()) || !m.keySet().containsAll(required)) throw new IllegalArgumentException(); }
    private static String text(Map<String,Object> m,String k,int max) { Object v=m.get(k); if (!(v instanceof String x) || x.isBlank() || x.strip().codePoints().count()>max) throw new IllegalArgumentException(); return x.strip(); }
    private static String optionalText(Map<String,Object> m,String k,int max) { return m.containsKey(k) ? text(m,k,max) : null; }
    private static UUID uuid(Map<String,Object> m,String k) { String x=text(m,k,36); try{return UUID.fromString(x);}catch(IllegalArgumentException e){throw new IllegalArgumentException();} }
    private static StudioSlot slot(Map<String,Object> m) { String x=text(m,"slot",20); try{return StudioSlot.valueOf(x);}catch(IllegalArgumentException e){throw new IllegalArgumentException();} }
    private static int requiredPosition(Map<String,Object> m) { if(!m.containsKey("position")) return 0; return parsePosition(m.get("position")); }
    private static Integer optionalPosition(Map<String,Object> m) { StudioSlot slot = slot(m); if(!m.containsKey("position")) { if(slot.allowsMultiple()) throw new IllegalArgumentException(); return null; } if(!slot.allowsMultiple()) throw new IllegalArgumentException(); return parsePosition(m.get("position")); }
    private static int parsePosition(Object v) { if(!(v instanceof Number n) || n.longValue()<0 || n.longValue()!=n.doubleValue() || n.longValue()>Integer.MAX_VALUE) throw new IllegalArgumentException(); return n.intValue(); }
    private static void exact(Map<String,Object> m) { if(!m.isEmpty()) throw new IllegalArgumentException(); }
}
