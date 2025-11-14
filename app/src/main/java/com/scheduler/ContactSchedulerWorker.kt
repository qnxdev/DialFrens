package com.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Duration
import java.time.Instant

class ContactSchedulerWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val gson = Gson()

    companion object {
        const val CHANNEL_ID = "contact_keeper_channel"
        private const val TAG = "ContactSchedulerWorker"

        // Logic to determine if a contact is due
        fun getDueContacts(contacts: List<Contact>): List<Contact> {
            val now = Instant.now().toEpochMilli()

            return contacts.filter { contact ->
                val last = contact.lastContacted
                val intervalMillis: Long = when (contact.recurrence) {
                    Recurrence.DAILY -> Duration.ofDays(1).toMillis()
                    Recurrence.WEEKLY -> Duration.ofDays(7).toMillis()
                    Recurrence.BIWEEKLY -> Duration.ofDays(14).toMillis()
                    Recurrence.MONTHLY -> Duration.ofDays(30).toMillis() // Approximation
                }

                // Check if the time difference is greater than the required interval
                now - last > intervalMillis
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting daily contact check...")

        // 1. Retrieve the contact list from SharedPreferences
        val sharedPrefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = sharedPrefs.getString(CONTACTS_KEY, null) ?: return Result.success()

        val type = object : TypeToken<MutableList<Contact>>() {}.type
        val contactList: List<Contact> = gson.fromJson(json, type)
        
        // 2. Determine which contacts are due
        val dueContacts = getDueContacts(contactList)

        if (dueContacts.isNotEmpty()) {
            Log.i(TAG, "${dueContacts.size} contacts are due.")
            // 3. Fire a notification for the first due contact (or all of them)
            sendNotification(dueContacts)
        } else {
            Log.i(TAG, "No contacts currently due.")
        }

        return Result.success()
    }

    private fun sendNotification(dueContacts: List<Contact>) {
        createNotificationChannel()

        val notificationManager = NotificationManagerCompat.from(applicationContext)

        // Create a summary notification if multiple contacts are due
        val summaryTitle = "${dueContacts.size} Friends/Family Need a Call!"
        val summaryText = dueContacts.joinToString { it.name }

        // Send a notification for each due contact
        dueContacts.forEachIndexed { index, contact ->
            // Intent to open the dialer pre-filled with the number
            val dialIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${contact.phoneNumber}")
                // Flag to ensure the activity starts fresh
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            // PendingIntent to launch the call when the user taps the button/notification
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                contact.id.hashCode(),
                dialIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email) // Placeholder icon
                .setContentTitle("Time to call ${contact.name}")
                .setContentText("Your scheduled ${contact.recurrence.name.lowercase()} call is due!")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_call,
                    "Call ${contact.name}",
                    pendingIntent
                )
                .setAutoCancel(true)
                .setGroup("contact_calls") // Group notifications

            // Use the contact ID hash code as the unique notification ID
            notificationManager.notify(contact.id.hashCode(), builder.build())
        }

        // Optional: Send a summary notification
        val summaryNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(summaryTitle)
            .setContentText(summaryText)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(summaryText))
            .setGroup("contact_calls")
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Notify with a unique ID for the group summary (e.g., 0)
        notificationManager.notify(0, summaryNotification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Contact Keeper Reminders"
            val descriptionText = "Reminders for scheduled calls to friends and family."
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
