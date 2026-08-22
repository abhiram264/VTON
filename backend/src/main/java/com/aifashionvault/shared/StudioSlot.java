package com.aifashionvault.shared;

public enum StudioSlot {
	HEAD,
	UPPER_BODY,
	LOWER_BODY,
	FULL_BODY,
	FEET,
	BAG,
	JEWELLERY,
	ACCESSORY;

	public boolean conflictsWith(StudioSlot other) {
		if (other == null) {
			throw new IllegalArgumentException("other is required");
		}
		if (this == FULL_BODY) {
			return other == UPPER_BODY || other == LOWER_BODY;
		}
		if (other == FULL_BODY) {
			return this == UPPER_BODY || this == LOWER_BODY;
		}
		return false;
	}

	public boolean allowsMultiple() {
		return this == JEWELLERY || this == ACCESSORY;
	}
}
