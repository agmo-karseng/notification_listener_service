package notification.listener.service;

import static notification.listener.service.NotificationUtils.getBitmapFromDrawable;
import static notification.listener.service.models.ActionCache.cachedNotifications;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.TransactionTooLargeException;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import notification.listener.service.models.Action;


@SuppressLint("OverrideAbstract")
@RequiresApi(api = VERSION_CODES.JELLY_BEAN_MR2)
public class NotificationListener extends NotificationListenerService {
    private static final String TAG = "NotificationListener";
    // Keep payload significantly under binder's practical transaction ceiling.
    private static final int MAX_BROADCAST_PAYLOAD_BYTES = 700 * 1024;
    private static final int MAX_ICON_BYTES = 120 * 1024;
    private static final int MAX_LARGE_ICON_BYTES = 180 * 1024;
    private static final int MAX_EXTRA_PICTURE_BYTES = 280 * 1024;

    private static NotificationListener instance;

    public static NotificationListener getInstance() {
        return instance;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @RequiresApi(api = VERSION_CODES.KITKAT)
    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        handleNotification(notification, false);
    }

    @RequiresApi(api = VERSION_CODES.KITKAT)
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        handleNotification(sbn, true);
    }

    @RequiresApi(api = VERSION_CODES.KITKAT)
    private void handleNotification(StatusBarNotification notification, boolean isRemoved) {
        if (notification == null || notification.getNotification() == null) {
            return;
        }
        String packageName = notification.getPackageName();
        Notification androidNotification = notification.getNotification();
        Bundle extras = androidNotification.extras;
        boolean isOngoing = (androidNotification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        byte[] appIcon = getAppIcon(packageName);
        byte[] largeIcon = null;
        Action action = isRemoved ? null : NotificationUtils.getQuickReplyAction(androidNotification, packageName);

        if (Build.VERSION.SDK_INT >= VERSION_CODES.M) {
            largeIcon = getNotificationLargeIcon(getApplicationContext(), androidNotification);
        }

        appIcon = trimBlob(appIcon, MAX_ICON_BYTES, "appIcon");
        largeIcon = trimBlob(largeIcon, MAX_LARGE_ICON_BYTES, "largeIcon");

        Intent intent = new Intent(NotificationConstants.INTENT);
        intent.putExtra(NotificationConstants.PACKAGE_NAME, packageName);
        intent.putExtra(NotificationConstants.ID, notification.getId());
        intent.putExtra(NotificationConstants.CAN_REPLY, action != null);
        intent.putExtra(NotificationConstants.IS_ONGOING, isOngoing);

        if (isRemoved) {
            cachedNotifications.remove(notification.getId());
        } else if (action != null) {
            cachedNotifications.put(notification.getId(), action);
        }

        intent.putExtra(NotificationConstants.NOTIFICATIONS_ICON, appIcon);
        intent.putExtra(NotificationConstants.NOTIFICATIONS_LARGE_ICON, largeIcon);

        String titleStr = null;
        String textStr = null;
        byte[] extrasPicture = null;
        boolean haveExtraPicture = false;

        if (extras != null) {
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            titleStr = title == null ? null : title.toString();
            textStr = text == null ? null : text.toString();

            intent.putExtra(NotificationConstants.NOTIFICATION_TITLE, titleStr);
            intent.putExtra(NotificationConstants.NOTIFICATION_CONTENT, textStr);
            intent.putExtra(NotificationConstants.IS_REMOVED, isRemoved);
            if (extras.containsKey(Notification.EXTRA_PICTURE)) {
                Bitmap bmp = (Bitmap) extras.get(Notification.EXTRA_PICTURE);
                if (bmp != null) {
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    // JPEG keeps transaction size lower than PNG for large photos.
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, stream);
                    extrasPicture = trimBlob(stream.toByteArray(), MAX_EXTRA_PICTURE_BYTES, "extraPicture");
                    haveExtraPicture = extrasPicture != null;
                } else {
                    Log.w(TAG, "Notification.EXTRA_PICTURE exists but is null.");
                }
            }
        }
        intent.putExtra(NotificationConstants.HAVE_EXTRA_PICTURE, haveExtraPicture);
        if (extrasPicture != null) {
            intent.putExtra(NotificationConstants.EXTRAS_PICTURE, extrasPicture);
        }

        // Final guard: drop heavy extras if transaction estimate is still large.
        int estimate = estimatePayloadBytes(packageName, titleStr, textStr, appIcon, largeIcon, extrasPicture);
        if (estimate > MAX_BROADCAST_PAYLOAD_BYTES) {
            intent.removeExtra(NotificationConstants.EXTRAS_PICTURE);
            intent.putExtra(NotificationConstants.HAVE_EXTRA_PICTURE, false);
            extrasPicture = null;
            estimate = estimatePayloadBytes(packageName, titleStr, textStr, appIcon, largeIcon, null);
        }
        if (estimate > MAX_BROADCAST_PAYLOAD_BYTES) {
            intent.removeExtra(NotificationConstants.NOTIFICATIONS_LARGE_ICON);
            largeIcon = null;
            estimate = estimatePayloadBytes(packageName, titleStr, textStr, appIcon, null, null);
        }
        if (estimate > MAX_BROADCAST_PAYLOAD_BYTES) {
            intent.removeExtra(NotificationConstants.NOTIFICATIONS_ICON);
            appIcon = null;
        }

        dispatchWithFallback(intent, packageName, notification.getId(), action != null, isOngoing, isRemoved, titleStr, textStr);
    }

    private void dispatchWithFallback(
            Intent intent,
            String packageName,
            int notificationId,
            boolean canReply,
            boolean isOngoing,
            boolean isRemoved,
            String title,
            String content
    ) {
        try {
            sendBroadcast(intent);
        } catch (RuntimeException e) {
            if (!isTransactionTooLarge(e)) {
                throw e;
            }
            Log.w(TAG, "TransactionTooLargeException while broadcasting notification. Sending lightweight payload.", e);
            Intent fallbackIntent = new Intent(NotificationConstants.INTENT);
            fallbackIntent.putExtra(NotificationConstants.PACKAGE_NAME, packageName);
            fallbackIntent.putExtra(NotificationConstants.ID, notificationId);
            fallbackIntent.putExtra(NotificationConstants.CAN_REPLY, canReply);
            fallbackIntent.putExtra(NotificationConstants.IS_ONGOING, isOngoing);
            fallbackIntent.putExtra(NotificationConstants.IS_REMOVED, isRemoved);
            fallbackIntent.putExtra(NotificationConstants.NOTIFICATION_TITLE, title);
            fallbackIntent.putExtra(NotificationConstants.NOTIFICATION_CONTENT, content);
            fallbackIntent.putExtra(NotificationConstants.HAVE_EXTRA_PICTURE, false);
            sendBroadcast(fallbackIntent);
        }
    }

    private boolean isTransactionTooLarge(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof TransactionTooLargeException) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }

    private byte[] trimBlob(byte[] blob, int maxBytes, String fieldName) {
        if (blob == null || blob.length <= maxBytes) {
            return blob;
        }
        Log.w(TAG, "Dropping oversized " + fieldName + " payload (" + blob.length + " bytes)");
        return null;
    }

    private int estimatePayloadBytes(
            String packageName,
            String title,
            String content,
            byte[] appIcon,
            byte[] largeIcon,
            byte[] extrasPicture
    ) {
        int bytes = 4 * 1024; // binder and bundle overhead buffer
        bytes += safeStringBytes(packageName);
        bytes += safeStringBytes(title);
        bytes += safeStringBytes(content);
        bytes += appIcon == null ? 0 : appIcon.length;
        bytes += largeIcon == null ? 0 : largeIcon.length;
        bytes += extrasPicture == null ? 0 : extrasPicture.length;
        return bytes;
    }

    private int safeStringBytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }


    public byte[] getAppIcon(String packageName) {
        try {
            PackageManager manager = getBaseContext().getPackageManager();
            Drawable icon = manager.getApplicationIcon(packageName);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            getBitmapFromDrawable(icon).compress(Bitmap.CompressFormat.PNG, 100, stream);
            return stream.toByteArray();
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    @RequiresApi(api = VERSION_CODES.M)
    private byte[] getNotificationLargeIcon(Context context, Notification notification) {
        try {
            Icon largeIcon = notification.getLargeIcon();
            if (largeIcon == null) {
                return null;
            }
            Drawable iconDrawable = largeIcon.loadDrawable(context);
            Bitmap iconBitmap = ((BitmapDrawable) iconDrawable).getBitmap();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            iconBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);

            return outputStream.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            Log.d("ERROR LARGE ICON", "getNotificationLargeIcon: " + e.getMessage());
            return null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public List<Map<String, Object>> getActiveNotificationData() {
        List<Map<String, Object>> notificationList = new ArrayList<>();
        StatusBarNotification[] activeNotifications = getActiveNotifications();

        for (StatusBarNotification sbn : activeNotifications) {
            Map<String, Object> notifData = new HashMap<>();
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            notifData.put("id", sbn.getId());
            notifData.put("packageName", sbn.getPackageName());
            notifData.put("title", extras.getCharSequence(Notification.EXTRA_TITLE) != null
                    ? extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                    : null);
            notifData.put("content", extras.getCharSequence(Notification.EXTRA_TEXT) != null
                    ? extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                    : null);
            boolean isOngoing = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            notifData.put("onGoing", isOngoing);

            notificationList.add(notifData);
        }
        return notificationList;
    }

}
