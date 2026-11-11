package com.example.roadsense

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.roadsense.data.Extras
import com.example.roadsense.data.Fmt
import com.example.roadsense.data.TripRepository

class TripSummaryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_trip_summary)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val tripId = intent.getLongExtra(Extras.TRIP_ID, -1L)
        val trip = TripRepository.get(this).getTrip(tripId)
        if (trip == null) {
            finish()
            return
        }

        val date = Fmt.dateTime(trip.startTime)
        val duration = Fmt.duration(trip.durationMillis)
        val distance = Fmt.distance(trip.distanceMeters)

        findViewById<TextView>(R.id.tvSummaryDate).text = date
        findViewById<RoadScoreGaugeView>(R.id.gaugeSummary).score = trip.roadScore
        findViewById<TextView>(R.id.tvSummaryDuration).text = getString(R.string.summary_duration, duration)
        findViewById<TextView>(R.id.tvSummaryDistance).text = getString(R.string.summary_distance, distance)
        findViewById<TextView>(R.id.tvSummaryAvgSpeed).text = getString(R.string.summary_avg_speed, trip.averageSpeedKmh)
        findViewById<TextView>(R.id.tvSummaryBumps).text = getString(R.string.summary_bumps, trip.bumpCount)
        findViewById<TextView>(R.id.tvSummaryScore).text = getString(R.string.summary_score, trip.roadScore)

        findViewById<Button>(R.id.btnViewBumps).setOnClickListener {
            startActivity(Intent(this, BumpDetailsActivity::class.java).putExtra(Extras.TRIP_ID, trip.id))
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            val text = getString(R.string.share_text, date, duration, distance, trip.bumpCount, trip.roadScore)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
        }
    }
}
