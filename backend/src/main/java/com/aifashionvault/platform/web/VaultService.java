package com.aifashionvault.platform.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aifashionvault.platform.objectstore.*;
import com.aifashionvault.shared.*;
import com.aifashionvault.vault.*;
import com.aifashionvault.vault.VaultContracts.*;

public final class VaultService {
	private final JdbcVaultStore store; private final ObjectStore media; private final Clock clock;
	public VaultService(JdbcVaultStore store,ObjectStore media){this(store,media,Clock.systemUTC());}
	public VaultService(JdbcVaultStore store,ObjectStore media,Clock clock){this.store=store;this.media=media;this.clock=clock;}

	public VaultItem create(UserId user,VaultItemCreateRequest request){
		validateAttributes(request.attributes()); UploadId upload=parseUpload(request.uploadId()); SourceReferenceId source=parseSource(request.sourceReferenceId());
		if(source!=null&&!store.sourceExists(source))throw problem(ProblemCode.VAULT_SOURCE_NOT_FOUND);
		ObjectMetadata metadata;
		try{metadata=media.head(new ObjectRef(StorageBoundary.TEMP,StorageKeys.stagingKey(user,upload).value()));}catch(ObjectStoreExceptions.StagedObjectMissingException e){throw problem(ProblemCode.VAULT_UPLOAD_NOT_CLAIMABLE);}catch(ObjectStoreExceptions.ObjectStoreUnavailableException e){throw problem(ProblemCode.VAULT_MEDIA_UNAVAILABLE);}
		ImageMediaType type=mediaType(metadata.contentType());
		UUID stableId=UUID.nameUUIDFromBytes((user.value()+":"+upload.value()).getBytes(StandardCharsets.UTF_8));
		VaultItemId id=new VaultItemId(stableId); StorageKey key=StorageKeys.persistentKey(user,StorageBoundary.VAULT,stableId,type);
		Optional<VaultItem> existing=store.findByStorageKeyForUser(key,user); if(existing.isPresent())return existing.get();
		ClaimResult claim;
		try{claim=media.claim(new ClaimCommand(user,upload,key,StorageBoundary.VAULT,"vault:"+user.value()+":"+upload.value(),metadata.version()));}catch(RuntimeException e){throw problem(ProblemCode.VAULT_MEDIA_UNAVAILABLE);}
		if(claim instanceof ClaimResult.Conflict)throw problem(ProblemCode.VAULT_UPLOAD_NOT_CLAIMABLE);
		if(claim instanceof ClaimResult.Replay){existing=store.findByStorageKeyForUser(key,user);if(existing.isPresent())return existing.get();throw problem(ProblemCode.VAULT_UPLOAD_NOT_CLAIMABLE);}
		VaultItem item=new VaultItem(id,user,key,source,request.name(),request.brand(),request.category(),request.subcategory(),request.color(),request.pattern(),request.attributes(),Instant.now(clock));
		try{return store.insert(item);}catch(RuntimeException e){try{media.delete(new ObjectRef(StorageBoundary.VAULT,key.value()));}catch(RuntimeException ignored){}throw e;}
	}
	public List<VaultItem> list(UserId user,Filters filters){return store.listForUser(user,filters);}
	public VaultItem get(UserId user,VaultItemId id){return store.findByIdForUser(id,user).orElseThrow(()->problem(ProblemCode.VAULT_ITEM_NOT_FOUND));}
	public VaultItem patch(UserId user,VaultItemId id,Patch patch){validateAttributes(patch.attributes().undefined()?null:patch.attributes().value());return store.patchForUser(id,user,patch).orElseThrow(()->problem(ProblemCode.VAULT_ITEM_NOT_FOUND));}
	public void delete(UserId user,VaultItemId id){VaultItem item=store.lockForDelete(id,user).orElseThrow(()->problem(ProblemCode.VAULT_ITEM_NOT_FOUND));if(store.isInUse(id))throw problem(ProblemCode.VAULT_ITEM_IN_USE);try{media.delete(new ObjectRef(StorageBoundary.VAULT,item.storageKey().value()));}catch(RuntimeException e){throw problem(ProblemCode.VAULT_MEDIA_UNAVAILABLE);}DeleteResult result=store.deleteForUser(id,user);if(result==DeleteResult.IN_USE)throw problem(ProblemCode.VAULT_ITEM_IN_USE);if(result==DeleteResult.NOT_FOUND)throw problem(ProblemCode.VAULT_ITEM_NOT_FOUND);}
	private static UploadId parseUpload(String value){try{return new UploadId(UUID.fromString(value.strip()));}catch(Exception e){throw problem(ProblemCode.VAULT_UPLOAD_NOT_CLAIMABLE);}}
	private static SourceReferenceId parseSource(String value){if(value==null)return null;try{return new SourceReferenceId(UUID.fromString(value));}catch(Exception e){throw problem(ProblemCode.VAULT_SOURCE_NOT_FOUND);}}
	private static ImageMediaType mediaType(String value){if("image/jpeg".equalsIgnoreCase(value))return ImageMediaType.JPEG;if("image/png".equalsIgnoreCase(value))return ImageMediaType.PNG;throw problem(ProblemCode.VAULT_UPLOAD_NOT_CLAIMABLE);}
	private static void validateAttributes(tools.jackson.databind.JsonNode value){if(value!=null&&!value.isObject())throw problem(ProblemCode.VAULT_INVALID_REQUEST);}
	static VaultProblem problem(ProblemCode code){return new VaultProblem(code);}
	public static final class VaultProblem extends RuntimeException{private final ProblemCode code;VaultProblem(ProblemCode code){this.code=code;}ProblemCode code(){return code;}}
}
