# RoadSense

## Overview
RoadSense is an Android app that turns a phone into a road quality scanner. While you travel by car or bike, the accelerometer detects bumps and potholes and GPS records where each one happened. After a trip you can see a road score for the journey and browse the rough spots that were recorded. All data stays on the device.

## Features
- Live trip recording with sensor status, a bump counter, current speed and a road-score gauge.
- Bump detection from vertical accelerometer jolts, graded minor, medium or severe.
- GPS location for each bump, plus trip distance and speed.
- A road score from 0 to 100 for every trip, shown on a custom gauge.
- Trip history, trip summary and a per-trip list of bumps.
- Share a trip summary with other apps and open a bump in a maps app.
- Local storage with the option to delete all data.

## Screens and navigation
| Screen (Activity) | What it shows | How you get there |
|---|---|---|
| `MainActivity` | Home with the **Start new trip** and **Trip history** buttons | App launcher |
| `LiveTripActivity` | Sensor status, bump count, speed, road-score gauge and an **End trip** button | Start new trip |
| `TripSummaryActivity` | Date, score gauge, duration, distance, average speed, bumps, and **Share** and **View bumps** buttons | End trip, or tap a trip in History |
| `HistoryActivity` | List of past trips (tap one to open its summary) and a **Delete all data** button | Trip history |
| `BumpDetailsActivity` | Each bump's time, severity and location, and an **Open in Maps** button | View bumps on the summary |

Flow: Home -> Live Trip -> (End trip) -> Trip Summary -> View bumps -> Bump Details. Home -> History -> Trip Summary -> Bump Details. Pressing Back on Live Trip also ends the trip.

## Sensor usage
- **Accelerometer:** gravity is estimated with a low-pass filter and removed from each reading. The remaining jolt is projected onto the gravity (vertical) direction. A value of 4 m/s² or more counts as a bump, with a one-second cool-down so one pothole is not counted twice. 8 m/s² is medium and 12 m/s² is severe.
- **GPS:** the location listener provides each bump's position, adds up trip distance and reports speed. Fixes with accuracy worse than 50 m are ignored. Location permission is requested when a trip starts; the screen shows if it is denied or unavailable.
- **Road score:** starts at 100 and drops with total bump severity per kilometre (trips under 0.5 km are treated as 0.5 km).

## Data storage
Trips and bumps are stored on the device with Room (SQLite) in the database `roadsense_room.db`:
- `Trip`: start time, end time, distance, bump count, road score.
- `Bump`: trip, timestamp, latitude, longitude, severity.

Bumps are linked to their trip and are deleted with it. **Delete all data** on the History screen removes everything. Queries currently run on the main thread because the data is small; moving them off it is a planned improvement.

## Setup and build
1. Open the project folder in Android Studio.
2. Click **Sync Project with Gradle Files** and wait for it to finish. Do this before running; the Room and KSP libraries are downloaded during sync.
3. Connect a phone (or start an emulator) and press **Run**.
4. Allow location permission when asked.

Requirements: minSdk 26, compile and target SDK 37, Kotlin, Android Gradle Plugin 9.4.1, Room 2.8.5, KSP 2.3.13. Bump detection and GPS need a real phone; an emulator will not produce real bumps. If the sync fails, check that the KSP and Room versions in `gradle/libs.versions.toml` match your Android Gradle Plugin.

## Milestone 1 requirements covered
- **Three distinctive functional layouts:** five layouts are implemented (`activity_main`, `activity_live_trip`, `activity_trip_summary`, `activity_history`, `activity_bump_details`), each with a different purpose.
- **User interaction:** buttons, tappable history and bump lists, a delete confirmation dialog and a runtime permission request.
- **Explicit intents:** moving between the app's own screens, with the trip ID passed as an Intent extra.
- **Implicit intents:** **Share** (`ACTION_SEND` with a chooser) and **Open in Maps** (`ACTION_VIEW` with a `geo:` URI).
