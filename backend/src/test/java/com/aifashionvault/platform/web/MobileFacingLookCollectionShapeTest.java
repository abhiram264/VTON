package com.aifashionvault.platform.web;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.platform.web.CollectionsController.CollectionItemResponse;
import com.aifashionvault.platform.web.CollectionsController.CollectionResponse;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StudioSlot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mobile Looks and Collections parsers read the outfit items off these two
 * shapes. Pin the wire contract they depend on without a Spring context.
 */
class MobileFacingLookCollectionShapeTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Instant CREATED_AT = Instant.parse("2026-08-30T12:00:00Z");

	private static final UUID ITEM_ONE = UUID.fromString("44444444-4444-4444-4444-444444444444");

	private static final UUID ITEM_TWO = UUID.fromString("55555555-5555-5555-5555-555555555555");

	@Test
	void lookResourceCarriesEveryOutfitItemWithItsVaultItemIdAndSlot() {
		LookResource look = new LookResource(
			UUID.randomUUID(),
			UUID.randomUUID(),
			UUID.randomUUID(),
			UUID.randomUUID(),
			null,
			"provider",
			"model",
			QualityDecision.PASS,
			CREATED_AT,
			List.of(new LookItemResource(ITEM_ONE, StudioSlot.UPPER_BODY),
				new LookItemResource(ITEM_TWO, StudioSlot.FEET)),
			new ReadCapability(URI.create("https://example.invalid/read"), CREATED_AT));

		JsonNode items = MAPPER.valueToTree(look).get("items");

		assertThat(items).hasSize(2);
		assertThat(items.get(0).get("vaultItemId").stringValue()).isEqualTo(ITEM_ONE.toString());
		assertThat(items.get(0).get("slot").stringValue()).isEqualTo("UPPER_BODY");
		assertThat(items.get(1).get("vaultItemId").stringValue()).isEqualTo(ITEM_TWO.toString());
		assertThat(items.get(1).get("slot").stringValue()).isEqualTo("FEET");
	}

	@Test
	void lookResourceOwnsAnImmutableItemList() {
		List<LookItemResource> mutable = new ArrayList<>(List.of(new LookItemResource(ITEM_ONE, StudioSlot.BAG)));
		LookResource look = new LookResource(UUID.randomUUID(), null, UUID.randomUUID(), null, null, "provider",
			"model", QualityDecision.PASS, CREATED_AT, mutable,
			new ReadCapability(URI.create("https://example.invalid/read"), CREATED_AT));

		mutable.add(new LookItemResource(ITEM_TWO, StudioSlot.JEWELLERY));

		assertThat(look.items()).hasSize(1);
		assertThatThrownBy(() -> look.items().add(new LookItemResource(ITEM_TWO, StudioSlot.JEWELLERY)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void collectionResponseCarriesMembershipAsStringVaultItemIds() {
		CollectionResponse collection = new CollectionResponse(UUID.randomUUID().toString(), "Wedding", CREATED_AT,
			CREATED_AT, List.of(new CollectionItemResponse(ITEM_ONE.toString(), CREATED_AT)));

		JsonNode items = MAPPER.valueToTree(collection).get("items");

		assertThat(items).hasSize(1);
		assertThat(items.get(0).get("vaultItemId").isString()).isTrue();
		assertThat(items.get(0).get("vaultItemId").stringValue()).isEqualTo(ITEM_ONE.toString());
	}
}
