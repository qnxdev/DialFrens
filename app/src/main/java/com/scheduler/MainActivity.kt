package com.scheduler

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Duration
import java.time.Instant

// --- Data Model ---
enum class Recurrence { DAILY, WEEKLY, BIWEEKLY, MONTHLY }

data class Contact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val recurrence: Recurrence,
    var lastContacted: Long // epoch milliseconds
)

// --- Constants ---
const val WORK_TAG = "contact_check_worker_tag"
const val PREFS_NAME = "contact_keeper_prefs"
const val CONTACTS_KEY = "scheduled_contacts"
const val REQUEST_CALL_PERMISSIONS = 101

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPrefs: SharedPreferences
    private val gson = Gson()
    private var contactList: MutableList<Contact> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // NOTE: In a real Android app, this would inflate the layout (R.layout.activity_main)
        // We will mock the content setup here:
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding = 32 // Mock padding
            // Mock Title
            addView(TextView(this@MainActivity).apply { text = "Scheduled Contact Keeper"; textSize = 24f; padding = 16 })
        }
        setContentView(layout)

        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Check Permissions and Request if needed
        checkAndRequestPermissions()

        // 2. Load contacts and set up mock data if empty
        loadContacts()
        if (contactList.isEmpty()) {
            setupMockData()
        }

        // 3. Set up the recurring WorkManager task
        scheduleDailyContactCheck()

        // 4. Mock UI Button for demonstration/manual check
        val scheduleButton = Button(this).apply {
            text = "Schedule Daily Check (WorkManager)"
            setOnClickListener {
                scheduleDailyContactCheck()
                Toast.makeText(this@MainActivity, "Daily check scheduled!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(scheduleButton)

        // 5. Display due contacts (Simple list)
        displayDueContacts(layout)
    }

    private fun checkAndRequestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALL_PERMISSIONS && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Call permission granted.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Call permission denied. Auto-dial won't work.", Toast.LENGTH_LONG).show()
        }
    }


    private fun setupMockData() {
        // Mock data set to be overdue
        val now = Instant.now().toEpochMilli()
        contactList = mutableListOf(
            Contact("1", "Mom", "5551234", Recurrence.DAILY, now - Duration.ofDays(2).toMillis()),
            Contact("2", "Aunt Jane", "5555678", Recurrence.WEEKLY, now - Duration.ofDays(10).toMillis()),
            Contact("3", "Cousin Tom", "5559012", Recurrence.BIWEEKLY, now - Duration.ofDays(20).toMillis()),
            Contact("4", "Old Friend", "5553456", Recurrence.MONTHLY, now - Duration.ofDays(50).toMillis())
        )
        saveContacts()
    }

    private fun loadContacts() {
        val json = sharedPrefs.getString(CONTACTS_KEY, null)
        if (json != null) {
            val type = object : TypeToken<MutableList<Contact>>() {}.type
            contactList = gson.fromJson(json, type)
        }
    }

    private fun saveContacts() {
        val json = gson.toJson(contactList)
        sharedPrefs.edit().putString(CONTACTS_KEY, json).apply()
    }

    private fun scheduleDailyContactCheck() {
        // Set constraints: requires device to be idle and charging to save battery
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(false) // Can run when not idle for reminders
            .setRequiresBatteryNotLow(true)
            .build()

        // Schedule the worker to run once every 24 hours (RecurringWorkRequest minimum is 15 minutes)
        val repeatingRequest = PeriodicWorkRequestBuilder<ContactSchedulerWorker>(
            repeatInterval = Duration.ofDays(1)
        ).setConstraints(constraints)
         .addTag(WORK_TAG)
         .build()

        // Enqueue the unique work request
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.UPDATE, // Replace existing work if it changes
            repeatingRequest
        )
    }

    private fun displayDueContacts(layout: LinearLayout) {
        val dueContacts = ContactSchedulerWorker.getDueContacts(contactList)
        
        layout.addView(TextView(this).apply {
            text = "\n--- Contacts Due Today ---"
            textSize = 20f
            padding = 16
        })

        if (dueContacts.isEmpty()) {
            layout.addView(TextView(this).apply { text = "No contacts currently due for a call!"; padding = 16 })
            return
        }

        dueContacts.forEach { contact ->
            layout.addView(TextView(this).apply {
                text = "${contact.name} (${contact.recurrence.name}) - DUE!"
                padding = 8
            })
        }
    }
}
