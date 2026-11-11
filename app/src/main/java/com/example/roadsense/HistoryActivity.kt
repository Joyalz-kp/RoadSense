package com.example.roadsense

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.roadsense.data.Extras
import com.example.roadsense.data.Fmt
import com.example.roadsense.data.Trip
import com.example.roadsense.data.TripRepository

class HistoryActivity : AppCompatActivity() {

    private lateinit var repo: TripRepository
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var trips: List<Trip> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repo = TripRepository.get(this)
        list = findViewById(R.id.listTrips)
        empty = findViewById(R.id.tvEmpty)

        list.setOnItemClickListener { _, _, position, _ ->
            val trip = trips[position]
            startActivity(Intent(this, TripSummaryActivity::class.java).putExtra(Extras.TRIP_ID, trip.id))
        }
        findViewById<Button>(R.id.btnDeleteAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    repo.deleteAll()
                    loadTrips()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadTrips()
    }

    private fun loadTrips() {
        trips = repo.getAllTrips()
        val rows = trips.map {
            "${Fmt.dateTime(it.startTime)}\n${Fmt.distance(it.distanceMeters)}  |  ${it.bumpCount} bumps  |  score ${it.roadScore}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        empty.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE
    }
}
