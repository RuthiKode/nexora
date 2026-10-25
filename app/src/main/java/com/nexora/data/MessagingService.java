package com.nexora.data;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.nexora.NexoraApp;

/**
 * Optional FCM path: displays pushes sent from the Firebase console (topic "all_students").
 * No feature depends on it; in-app notifications come from Firestore via {@link SyncWorker}.
 */
public class MessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        RemoteMessage.Notification n = message.getNotification();
        String title = n != null && n.getTitle() != null ? n.getTitle() : message.getData().get("title");
        String body = n != null && n.getBody() != null ? n.getBody() : message.getData().get("body");
        if (title == null && body == null) return;
        SyncWorker.show(this, message.hashCode(), title == null ? "NEXORA" : title, body == null ? "" : body,
                SyncWorker.CHANNEL_ANNOUNCEMENTS);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        NexoraApp.container(this).auth.saveFcmToken(token);
    }
}
