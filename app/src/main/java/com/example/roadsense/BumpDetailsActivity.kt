package com.example.roadsense

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.roadsense.data.Bump
import com.example.roadsense.data.Extras
import com.example.roadsense.data.Fmt
import com.example.roadsense.data.TripRepository

class BumpDetailsActivity : AppCompatActivity() {

    private var bumps: List<Bump> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_bump_details)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val tripId = intent.getLongExtra(Extras.TRIP_ID, -1L)
        bumps = TripRepository.get(this).getBumps(tripId)

        val list = findViewById<ListView>(R.id.listBumps)
        val rows = bumps.map { b ->
            val where = if (b.hasLocation) String.format("%.5f, %.5f", b.latitude, b.longitude) else "location unknown"
            "${Fmt.time(b.timestamp)}  |  ${severityLabel(b.severity)}\n$where"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, rows)
        findViewById<TextView>(R.id.tvNoBumps).visibility = if (bumps.isEmpty()) View.VISIBLE else View.GONE

        findViewById<Button>(R.id.btnOpenInMaps).setOnClickListener {
            val position = list.checkedItemPosition
            if (position == ListView.INVALID_POSITION) {
                Toast.makeText(this, R.string.select_bump_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bump = bumps[position]
            if (!bump.hasLocation) return@setOnClickListener
            val uri = Uri.parse("geo:${bump.latitude},${bump.longitude}?q=${bump.latitude},${bump.longitude}(Bump)")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun severityLabel(severity: Int) = when (severity) {
        3 -> "severe"
        2 -> "medium"
        else -> "minor"
    }
}
