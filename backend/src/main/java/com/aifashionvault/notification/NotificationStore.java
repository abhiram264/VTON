package com.aifashionvault.notification;

import java.util.Optional;

import com.aifashionvault.shared.NotificationId;

public interface NotificationStore {

	void insert(Notification notification);

	Optional<Notification> findById(NotificationId id);
}
