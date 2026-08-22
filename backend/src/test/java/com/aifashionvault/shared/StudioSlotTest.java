package com.aifashionvault.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioSlotTest {

	@Test
	void constantsAreExactlyTheFrozenSlotsInOrder() {
		assertThat(StudioSlot.values()).containsExactly(
			StudioSlot.HEAD,
			StudioSlot.UPPER_BODY,
			StudioSlot.LOWER_BODY,
			StudioSlot.FULL_BODY,
			StudioSlot.FEET,
			StudioSlot.BAG,
			StudioSlot.JEWELLERY,
			StudioSlot.ACCESSORY);
	}

	@Test
	void fullBodyConflictsOnlyWithUpperAndLowerBodyAndIsSymmetric() {
		assertThat(StudioSlot.FULL_BODY.conflictsWith(StudioSlot.UPPER_BODY)).isTrue();
		assertThat(StudioSlot.UPPER_BODY.conflictsWith(StudioSlot.FULL_BODY)).isTrue();
		assertThat(StudioSlot.FULL_BODY.conflictsWith(StudioSlot.LOWER_BODY)).isTrue();
		assertThat(StudioSlot.LOWER_BODY.conflictsWith(StudioSlot.FULL_BODY)).isTrue();

		assertThat(StudioSlot.FULL_BODY.conflictsWith(StudioSlot.HEAD)).isFalse();
		assertThat(StudioSlot.HEAD.conflictsWith(StudioSlot.FULL_BODY)).isFalse();
		assertThat(StudioSlot.FULL_BODY.conflictsWith(StudioSlot.FEET)).isFalse();
		assertThat(StudioSlot.FEET.conflictsWith(StudioSlot.FULL_BODY)).isFalse();
		assertThat(StudioSlot.FULL_BODY.conflictsWith(StudioSlot.BAG)).isFalse();
		assertThat(StudioSlot.BAG.conflictsWith(StudioSlot.FULL_BODY)).isFalse();
		assertThat(StudioSlot.FULL_BODY.conflictsWith(StudioSlot.JEWELLERY)).isFalse();
		assertThat(StudioSlot.JEWELLERY.conflictsWith(StudioSlot.FULL_BODY)).isFalse();
		assertThat(StudioSlot.FULL_BODY.conflictsWith(StudioSlot.ACCESSORY)).isFalse();
		assertThat(StudioSlot.ACCESSORY.conflictsWith(StudioSlot.FULL_BODY)).isFalse();
	}

	@Test
	void noSlotConflictsWithItselfAndUnrelatedPairsDoNotConflict() {
		for (StudioSlot slot : StudioSlot.values()) {
			assertThat(slot.conflictsWith(slot)).isFalse();
		}

		assertThat(StudioSlot.UPPER_BODY.conflictsWith(StudioSlot.LOWER_BODY)).isFalse();
		assertThat(StudioSlot.LOWER_BODY.conflictsWith(StudioSlot.UPPER_BODY)).isFalse();
		assertThat(StudioSlot.HEAD.conflictsWith(StudioSlot.FEET)).isFalse();
		assertThat(StudioSlot.BAG.conflictsWith(StudioSlot.JEWELLERY)).isFalse();
		assertThat(StudioSlot.ACCESSORY.conflictsWith(StudioSlot.HEAD)).isFalse();
	}

	@Test
	void allowsMultipleIsTrueOnlyForJewelleryAndAccessory() {
		assertThat(StudioSlot.JEWELLERY.allowsMultiple()).isTrue();
		assertThat(StudioSlot.ACCESSORY.allowsMultiple()).isTrue();
		assertThat(StudioSlot.HEAD.allowsMultiple()).isFalse();
		assertThat(StudioSlot.UPPER_BODY.allowsMultiple()).isFalse();
		assertThat(StudioSlot.LOWER_BODY.allowsMultiple()).isFalse();
		assertThat(StudioSlot.FULL_BODY.allowsMultiple()).isFalse();
		assertThat(StudioSlot.FEET.allowsMultiple()).isFalse();
		assertThat(StudioSlot.BAG.allowsMultiple()).isFalse();
	}

	@Test
	void conflictsWithRejectsNullOther() {
		assertThatThrownBy(() -> StudioSlot.HEAD.conflictsWith(null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
