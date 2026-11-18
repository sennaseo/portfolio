package com.mybudget.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 은행·카드 앱과 문자 알림을 읽어 거래로 기록한다. 알림 내용은 기기 밖으로 나가지 않는다. */
class NotiListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            val ex = n.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            if (text.isNullOrBlank() && title.isNullOrBlank()) return

            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) {
                sbn.packageName
            }
            val tx = Store.ingest(this, sbn.packageName, label, title, text, sbn.postTime) ?: return
            askCategory(this, tx)
        } catch (e: Exception) {
            // 알림 하나 때문에 서비스가 죽지 않도록 한다
        }
    }

    companion object {
        const val CHANNEL = "assign"
        const val EXTRA_TX = "tx_id"

        fun askCategory(ctx: Context, tx: Tx) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "미분류 지출 알림", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_TX, tx.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, tx.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle("${tx.merchant} ${won(tx.amount)}")
                .setContentText("어느 예산에서 쓴 돈인지 골라주세요")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try { nm.notify(tx.id.toInt(), n) } catch (e: SecurityException) { }
        }
    }
}
