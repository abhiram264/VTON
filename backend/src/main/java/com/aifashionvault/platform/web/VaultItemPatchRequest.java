package com.aifashionvault.platform.web;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.aifashionvault.normalization.ProductCategory;
import com.aifashionvault.vault.VaultContracts.JsonNullable;
import com.aifashionvault.vault.VaultContracts.Patch;

final class VaultItemPatchRequest {
	private static final Set<String> ALLOWED=Set.of("name","brand","category","subcategory","color","pattern","attributes");
	private VaultItemPatchRequest() {}

	static Patch parse(ObjectNode node) {
		for (String name : node.propertyNames()) if(!ALLOWED.contains(name)) throw new IllegalArgumentException("unknown property");
		return new Patch(text(node,"name"),text(node,"brand"),category(node),text(node,"subcategory"),text(node,"color"),text(node,"pattern"),attributes(node));
	}
	private static JsonNullable<String> text(ObjectNode n,String k){if(!n.has(k))return JsonNullable.undefinedValue();JsonNode v=n.get(k);if(v.isNull())return JsonNullable.of(null);if(!v.isString())throw new IllegalArgumentException("invalid "+k);return JsonNullable.of(v.stringValue());}
	private static JsonNullable<ProductCategory> category(ObjectNode n){if(!n.has("category"))return JsonNullable.undefinedValue();JsonNode v=n.get("category");if(v.isNull())return JsonNullable.of(null);if(!v.isString())throw new IllegalArgumentException("invalid category");return JsonNullable.of(ProductCategory.valueOf(v.stringValue()));}
	private static JsonNullable<JsonNode> attributes(ObjectNode n){if(!n.has("attributes"))return JsonNullable.undefinedValue();JsonNode v=n.get("attributes");if(v.isNull())return JsonNullable.of(null);if(!v.isObject())throw new IllegalArgumentException("invalid attributes");return JsonNullable.of(v);}
}
