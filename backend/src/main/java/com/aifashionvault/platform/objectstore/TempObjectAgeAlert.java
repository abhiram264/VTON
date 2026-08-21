package com.aifashionvault.platform.objectstore;

import java.time.Duration;

@FunctionalInterface
public interface TempObjectAgeAlert {

	void aged(ObjectRef object, Duration age);
}
