package com.example.hazarddetectionapp

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class CaptureActivity : AppCompatActivity() {

    // Firestore
    private lateinit var db: FirebaseFirestore

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLat: Double? = null
    private var currentLon: Double? = null
    private val REQUEST_LOCATION_PERMISSION = 300

    // UI
    private lateinit var imgPreview: ImageView
    private lateinit var btnCamera: Button
    private lateinit var btnGallery: Button
    private lateinit var btnSubmit: Button
    private lateinit var btnDeleteImage: ImageButton

    // Activity result launchers
    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>

    private var selectedBitmap: Bitmap? = null  // store selected image

    // 🔔 Firestore listener + start time
    private var hazardListener: ListenerRegistration? = null
    private var listenerStartTime: Long = 0L

    // 🔑 Unique user ID per device
    private var userId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        // Generate or load persistent userId for this device
        userId = getOrCreateUserId()

        createNotificationChannel()
        checkNotificationPermission()

        // init Firestore
        db = FirebaseFirestore.getInstance()

        // Location init
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        checkLocationPermissionAndFetch()

        // View bindings
        imgPreview = findViewById(R.id.imgPreview)
        btnCamera = findViewById(R.id.btnCamera)
        btnGallery = findViewById(R.id.btnGallery)
        btnSubmit = findViewById(R.id.btnSubmit)
        btnDeleteImage = findViewById(R.id.btnDeleteImage)

        setupActivityResultLaunchers()
        setupPermissionLauncher()

        btnCamera.setOnClickListener {
            checkCameraPermissionAndOpen()
        }

        btnGallery.setOnClickListener {
            openGallery()
        }

        btnSubmit.setOnClickListener {
            val bitmap = selectedBitmap
            if (bitmap == null) {
                Toast.makeText(
                    this,
                    "Please select or capture an image first",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // 1) Show thank-you dialog to the user
            showThankYouDialog()

            // 2) Save hazard (with location) to Firestore
            saveHazardToCloud()
        }

        btnDeleteImage.setOnClickListener {
            imgPreview.setImageResource(0)
            selectedBitmap = null
            Toast.makeText(this, "Image removed", Toast.LENGTH_SHORT).show()
        }
    }

    // Generate / load unique userId (per install)
    private fun getOrCreateUserId(): String {
        val prefs = getSharedPreferences("hazard_prefs", MODE_PRIVATE)
        var id = prefs.getString("user_id", null)

        if (id == null) {
            id = "user_" + java.util.UUID.randomUUID().toString().take(16)
            prefs.edit().putString("user_id", id).apply()
        }
        return id
    }

    // ---------------- DISTANCE + LISTENER ----------------

    private fun distanceInKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        fun toRad(x: Double) = x * Math.PI / 180.0

        val R = 6371.0 // Earth radius in km
        val dLat = toRad(lat2 - lat1)
        val dLon = toRad(lon2 - lon1)

        val a = sin(dLat / 2).pow(2.0) +
                cos(toRad(lat1)) * cos(toRad(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    // 🔥 Listen for NEW hazards only, based on timestamp
    private fun startHazardListener() {
        // Record when we started listening
        listenerStartTime = System.currentTimeMillis()

        if (hazardListener != null) {
            // Already listening
            return
        }

        hazardListener = db.collection("hazards")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    e.printStackTrace()
                    return@addSnapshotListener
                }

                val userLat = currentLat
                val userLon = currentLon

                if (snapshots == null || userLat == null || userLon == null) {
                    return@addSnapshotListener
                }

                for (dc in snapshots.documentChanges) {
                    if (dc.type == DocumentChange.Type.ADDED) {
                        val doc = dc.document

                        // Only react to hazards created AFTER we started listening
                        val hazardTimestamp = doc.getLong("timestamp") ?: 0L
                        if (hazardTimestamp < listenerStartTime) {
                            continue
                        }

                        val hazardLat = doc.getDouble("lat") ?: continue
                        val hazardLon = doc.getDouble("lon") ?: continue

                        // OPTIONAL: ignore my own hazards
                         val hazardUserId = doc.getString("user_id")
                         if (hazardUserId == userId)
                             continue

                        val dist = distanceInKm(userLat, userLon, hazardLat, hazardLon)
                        val radiusKm = 5.0  // notification radius in km

                        if (dist <= radiusKm) {
                            showHazardNotification(dist)
                        }
                    }
                }
            }
    }

    // ---------------- NOTIFICATION PERMISSION + CHANNEL ----------------

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "hazard_channel"
            val channelName = "Hazard Alerts"
            val channelDesc = "Notifications for nearby road hazards"

            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = channelDesc
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ---------------- LOCATION ----------------

    private fun checkLocationPermissionAndFetch() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
        } else {
            fetchLastLocation()
        }
    }

    private fun fetchLastLocation() {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    currentLat = location.latitude
                    currentLon = location.longitude

                    // Start listener only AFTER we have location
                    startHazardListener()

                    // Debug toast
                    Toast.makeText(
                        this,
                        "Location: $currentLat, $currentLon",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "Unable to fetch location", Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                fetchLastLocation()
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- FIREBASE: SAVE HAZARD ----------------

    private fun saveHazardToCloud() {
        val lat = currentLat
        val lon = currentLon

        val hazardData = hashMapOf(
            "user_id" to userId,               // now unique per device
            "lat" to lat,
            "lon" to lon,
            "timestamp" to System.currentTimeMillis(),
            "source" to "android_app"
        )

        db.collection("hazards")
            .add(hazardData)
            .addOnSuccessListener {
                // Optional: debug toast
                // Toast.makeText(this, "Saved hazard ID: ${it.id}", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
                Toast.makeText(this, "Failed to save hazard to cloud", Toast.LENGTH_SHORT).show()
            }
    }

    // ---------------- CAMERA & GALLERY ----------------

    private fun setupPermissionLauncher() {
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                openCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission denied. Please allow it to use camera.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupActivityResultLaunchers() {
        // Camera result
        cameraLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val bitmap = data?.extras?.get("data") as? Bitmap
                if (bitmap != null) {
                    selectedBitmap = bitmap
                    imgPreview.setImageBitmap(bitmap)
                } else {
                    Toast.makeText(this, "No image returned from camera", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Camera cancelled", Toast.LENGTH_SHORT).show()
            }
        }

        // Gallery result
        galleryLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                try {
                    val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    selectedBitmap = bitmap
                    imgPreview.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkCameraPermissionAndOpen() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            openCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val resolveInfo = cameraIntent.resolveActivity(packageManager)
        if (resolveInfo != null) {
            cameraLauncher.launch(cameraIntent)
        } else {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        galleryLauncher.launch("image/*")
    }

    // ---------------- DIALOG + LOCAL NOTIFICATION ----------------

    private fun showThankYouDialog() {
        AlertDialog.Builder(this)
            .setTitle("Thank You")
            .setMessage("Thank you for submitting the hazard. Drive safe!")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showHazardNotification(distanceKm: Double) {
        val channelId = "hazard_channel"

        val contentText =
            "A road hazard was reported about ${"%.1f".format(distanceKm)} km from you. Drive safe!"

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.hazardlogo)  // replace with your own icon if you want
            .setContentTitle("Hazard detected in your area")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hazardListener?.remove()
    }
}
