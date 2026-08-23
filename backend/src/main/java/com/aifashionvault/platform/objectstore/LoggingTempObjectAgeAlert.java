package com.aifashionvault.platform.objectstore;

import java.time.Duration;

public final class LoggingTempObjectAgeAlert implements TempObjectAgeAlert {

	private static final System.Logger LOG = System.getLogger(LoggingTempObjectAgeAlert.class.getName());

	private LastAlert lastAlert;

	public record LastAlert(String key, long ageHours) {

		@Override
		public String toString() {
			return "temp object aged key=" + key + " ageHours=" + ageHours;
		}
	}

	@Override
	public void aged(ObjectRef object, Duration age) {
		if (object == null || age == null) {
			throw new IllegalArgumentException("object and age are required");
		}
		LastAlert event = new LastAlert(object.objectKey(), age.toHours());
		this.lastAlert = event;
		LOG.log(System.Logger.Level.WARNING, event.toString());
	}

	public LastAlert lastAlert() {
		return lastAlert;
	}

	public String lastMessage() {
		return lastAlert == null ? null : lastAlert.toString();
	}
}
