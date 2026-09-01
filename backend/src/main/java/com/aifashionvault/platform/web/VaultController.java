package com.aifashionvault.platform.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.aifashionvault.normalization.ProductCategory;
import com.aifashionvault.shared.*;
import com.aifashionvault.vault.VaultItem;
import com.aifashionvault.vault.VaultContracts.*;
import com.aifashionvault.platform.web.VaultService.VaultProblem;

@RestController @RequestMapping("/vault/items")
public class VaultController {
	private static final Set<String> CREATE_FIELDS=Set.of("uploadId","sourceReferenceId","name","brand","category","subcategory","color","pattern","attributes");
	private final ObjectProvider<VaultService> services; public VaultController(ObjectProvider<VaultService> services){this.services=services;}
	@PostMapping public ResponseEntity<VaultItemResponse> create(@AuthenticationPrincipal UserId user,@RequestBody ObjectNode body){
		for(String name:body.propertyNames())if(!CREATE_FIELDS.contains(name))throw new IllegalArgumentException("unknown property");
		VaultItemCreateRequest request=new VaultItemCreateRequest(text(body,"uploadId"),text(body,"sourceReferenceId"),text(body,"name"),text(body,"brand"),body.has("category")?ProductCategory.valueOf(text(body,"category")):null,text(body,"subcategory"),text(body,"color"),text(body,"pattern"),body.get("attributes"));
		return ResponseEntity.status(201).body(response(service().create(user,request)));
	}
	@GetMapping public List<VaultItemResponse> list(@AuthenticationPrincipal UserId user,@RequestParam(required=false) String category,@RequestParam(required=false) String collection,@RequestParam(required=false) String brand,@RequestParam(required=false) String color,@RequestParam(required=false) String search,@RequestParam(required=false) String source,@RequestParam(required=false) String createdDate){Filters filters=new Filters(category(category),uuid(collection),text(brand),text(color),text(search),text(source),date(createdDate));return service().list(user,filters).stream().map(VaultController::response).toList();}
	@GetMapping("/{id}") public VaultItemResponse get(@AuthenticationPrincipal UserId user,@PathVariable String id){return response(service().get(user,itemId(id)));}
	@PatchMapping("/{id}") public VaultItemResponse patch(@AuthenticationPrincipal UserId user,@PathVariable String id,@RequestBody ObjectNode body){Patch patch=VaultItemPatchRequest.parse(body);if(patch.empty())throw VaultService.problem(ProblemCode.VAULT_INVALID_REQUEST);return response(service().patch(user,itemId(id),patch));}
	@DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@AuthenticationPrincipal UserId user,@PathVariable String id){service().delete(user,itemId(id));}
	@ExceptionHandler(VaultProblem.class) ResponseEntity<Problem> problem(VaultProblem e){return ResponseEntity.status(status(e.code())).contentType(MediaType.APPLICATION_JSON).body(new Problem(e.code().name()));}
	@ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<Problem> invalid(){return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(new Problem(ProblemCode.VAULT_INVALID_REQUEST.name()));}
	private static VaultItemId itemId(String v){try{return new VaultItemId(UUID.fromString(v));}catch(Exception e){throw VaultService.problem(ProblemCode.VAULT_ITEM_NOT_FOUND);}}
	private static ProductCategory category(String v){if(v==null)return null;v=text(v);try{return ProductCategory.valueOf(v);}catch(Exception e){throw VaultService.problem(ProblemCode.VAULT_INVALID_REQUEST);}}
	private static UUID uuid(String v){if(v==null)return null;v=text(v);try{return UUID.fromString(v);}catch(Exception e){throw VaultService.problem(ProblemCode.VAULT_INVALID_REQUEST);}}
	private static LocalDate date(String v){if(v==null)return null;v=text(v);try{return LocalDate.parse(v);}catch(Exception e){throw VaultService.problem(ProblemCode.VAULT_INVALID_REQUEST);}}
	private static String text(ObjectNode n,String k){JsonNode v=n.get(k);if(v==null||v.isNull())return null;if(!v.isString())throw VaultService.problem(ProblemCode.VAULT_INVALID_REQUEST);return v.stringValue();}
	private static String text(String v){if(v==null)return null;String s=v.strip();if(s.isEmpty())throw VaultService.problem(ProblemCode.VAULT_INVALID_REQUEST);return s;}
	private VaultService service(){try{VaultService service=services.getIfAvailable();if(service!=null)return service;}catch(NoUniqueBeanDefinitionException ignored){}throw VaultService.problem(ProblemCode.VAULT_MEDIA_UNAVAILABLE);}
	private static VaultItemResponse response(VaultItem v){return new VaultItemResponse(v.id().value().toString(),v.sourceReferenceId()==null?null:v.sourceReferenceId().value().toString(),v.name(),v.brand(),v.category(),v.subcategory(),v.color(),v.pattern(),v.attributes(),v.createdAt());}
	private static HttpStatus status(ProblemCode code){return switch(code){case VAULT_ITEM_NOT_FOUND,VAULT_SOURCE_NOT_FOUND->HttpStatus.NOT_FOUND;case VAULT_ITEM_IN_USE->HttpStatus.CONFLICT;case VAULT_MEDIA_UNAVAILABLE->HttpStatus.SERVICE_UNAVAILABLE;default->HttpStatus.BAD_REQUEST;};}
	record Problem(String code){}
}
