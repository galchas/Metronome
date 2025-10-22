### Quick start
Here’s the shortest path to show a sounding MetronomeView inside your app.

1) Add the library to your project
- Option A (source module, easiest to iterate during development)
    - Copy the library module folder from this repo into your project (the module here is named `:app`, you can rename it to `:metronome`).
    - settings.gradle[.kts]
      ```kotlin
      include(":metronome")
      project(":metronome").projectDir = File(rootDir, "metronome") // adjust the path/name
      ```
    - Your app module build.gradle[.kts]
      ```kotlin
      dependencies {
          implementation(project(":metronome"))
      }
      ```

- Option B (AAR binary, no source)
    - Build the AAR from the library module (Android Studio → Make Project, or Gradle task `assembleRelease`).
    - Copy the AAR into your app at `app/libs/` and add:
      ```kotlin
      repositories { flatDir { dirs("libs") } }
      dependencies { implementation(files("libs/metronome-release.aar")) }
      ```

2) Turn on Data Binding in your app
   The view is implemented with Android Data Binding. Enable it in your app module:
```kotlin
android {
    buildFeatures {
        dataBinding = true
    }
}
```

3) Use a Material-compatible theme
   The view uses Material components. Make sure your app theme inherits from Material (e.g.):
```xml
<style name="Theme.MyApp" parent="Theme.Material3.DayNight.NoActionBar"/>
```

4) Add the MetronomeView to any layout
```xml
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.bobek.metronome.view.MetronomeView
        android:id="@+id/metronomeView"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"/>
</androidx.constraintlayout.widget.ConstraintLayout>
```
Note: The host Activity/Fragment should be a `LifecycleOwner` (e.g., `AppCompatActivity`). Most modern Activities already are.

5) Request notification permission on Android 13+
   The metronome plays sound via a foreground service that posts a notification. If your `targetSdk` is 33+ (Android 13), you must request `POST_NOTIFICATIONS` at runtime:
- In AndroidManifest.xml:
  ```xml
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  ```
- In your Activity:
  ```kotlin
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
          launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
  }
  ```

6) (Android 14+) Foreground service type permission
   If your app targets Android 14+ (SDK 34), also declare the media playback foreground service permission to avoid startup issues:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```
The service itself is declared in the library’s manifest and will be merged automatically; you generally do not need to add the `<service>` entry yourself.

7) Control it from code (optional)
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val metronomeView = findViewById<com.bobek.metronome.view.MetronomeView>(R.id.metronomeView)
        metronomeView.setTempo(100) // set initial BPM
        // metronomeView.start()    // start playback programmatically (optional)
    }
}
```

### What’s included and how it behaves
- Full UI: beats, subdivisions, gaps (mute beats), tempo slider and text fields, tap-tempo, start/stop, beat visualization.
- Sound: The view starts/binds to `MetronomeService` from the library for audio playback and listens to tick broadcasts to sync the visual blink.
- Fallback: If the service can’t run (e.g., permission denied), the view still blinks visually using an internal scheduler but will be silent.

### Common pitfalls and fixes
- InflateException / `DataBinderMapperImpl` not found
    - Cause: Data Binding not enabled in your app.
    - Fix: Enable `android.buildFeatures.dataBinding = true` in your app module.

- No sound on Android 13+
    - Cause: Missing `POST_NOTIFICATIONS` runtime permission; the service can’t post its foreground notification.
    - Fix: Add the permission to the manifest and request it at runtime.

- No sound on Android 14+
    - Cause: Missing `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission while targeting SDK 34.
    - Fix: Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>`.

- Theme issues (controls look wrong)
    - Ensure your app theme is Material3-compatible or add Material theme overlays around the view.

### Gradle/SDK matrix (for reference)
- Min SDK: 21
- Compile/Target SDK used by the library: 36 (your app can compile/target equal or higher; just keep the permissions above in mind for 33+ and 34+)

### Quick checklist
- [ ] Add dependency (module or AAR)
- [ ] Enable Data Binding in your app module
- [ ] Use Material3 theme
- [ ] Add `<com.bobek.metronome.view.MetronomeView/>` to your layout
- [ ] On Android 13+: add/request `POST_NOTIFICATIONS`
- [ ] On Android 14+: add `FOREGROUND_SERVICE_MEDIA_PLAYBACK`

If you share how your app is structured (Gradle Groovy vs Kotlin, module names, min/target SDK), I can give you copy‑paste snippets tailored precisely to your setup.
