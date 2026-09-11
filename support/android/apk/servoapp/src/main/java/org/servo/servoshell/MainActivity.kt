/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import org.servo.servoview.Servo
import org.servo.servoview.ServoView

// Roves fork: upstream Servo's own reference "servoshell" browser UI (address bar, back/
// forward/refresh buttons, a Settings screen, a History screen, and manifest intent-filters
// offering this app as a system-wide browser/URL handler) has all been removed -- this is
// meant to look and behave like a native game, not a browser a player could set as their
// default. See AndroidManifest.xml for the matching intent-filter/activity removal, and
// CUSTOMIZATIONS.md for the full writeup (mirrors the desktop shell's own, much older
// "remove toolbar and tab strip" customization -- this is the same intent, just never ported
// to the Android target when it was added).
class MainActivity : ComponentActivity(), Servo.Client {
    private lateinit var servoView: ServoView

    private var canGoBackState = mutableStateOf(false)
    private var mediaSession: MediaSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        servoView = ServoView(this)

        // `servoThemeColor` is a Gradle `resValue` (see servoapp/build.gradle.kts), sourced
        // from the bundled game's own manifest `theme_color` (or `mach bundle`'s own
        // --android-theme-color override) -- empty string by default, meaning "no theme_color
        // was set, leave the status bar at its normal theme color" rather than forcing some
        // placeholder color. `Color.parseColor` only understands `#rrggbb`/`#aarrggbb` and a
        // handful of named colors (not arbitrary CSS like `rgb(...)`), so an unparseable value
        // is caught and ignored rather than crashing the app on launch.
        val themeColor = getString(R.string.servoThemeColor)
        if (themeColor.isNotBlank()) {
            try {
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.parseColor(themeColor)
            } catch (e: IllegalArgumentException) {
                Log.w("MainActivity", "Ignoring unparseable --android-theme-color/theme_color '$themeColor'", e)
            }
        }

        setContent {
            AndroidView(factory = { _ -> servoView }, modifier = Modifier)
            BackHandler(enabled = canGoBackState.value) {
                servoView.goBack()
            }
        }

        servoView.setClient(this)
        servoView.requestFocus()

        val sdcard = getExternalFilesDir("")
        val host = sdcard!!.toPath().resolve("android_hosts").toString()
        try {
            Os.setenv("HOST_FILE", host, false)
        } catch (e: ErrnoException) {
            e.printStackTrace()
        }

        val intent = getIntent()
        val args = intent.getStringExtra("servoargs")
        val log = intent.getStringExtra("servolog")
        servoView.setServoArgs(args, log, false)

        // No "open with" intent to handle -- the manifest's own LAUNCHER-only intent-filter
        // (see AndroidManifest.xml) means this activity is never started any other way. Load
        // whatever `mach bundle --android --content-dir` packed into the APK's own assets (see
        // post_build_commands.py's `_bundle_android`), if anything was bundled at all. A plain
        // engine-shell build with no bundled content (e.g. .github/workflows/android.yml's
        // per-commit build) has no assets/www/index.html, so this 404s inside Servo itself --
        // same as any other missing local file, no special-casing needed here.
        servoView.loadUri("file:///android_asset/www/index.html")
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.hideMediaSessionControls()
    }

    override fun onImeShow() {
        getSystemService<InputMethodManager>()?.showSoftInput(servoView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onImeHide() {
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(servoView.windowToken, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onAlert(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .show()
    }

    override fun onLoadStarted() {
    }

    override fun onLoadEnded() {
    }

    override fun onTitleChanged(title: String) {
    }

    override fun onUrlChanged(url: String) {
    }

    override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {
        canGoBackState.value = canGoBack
    }

    override fun onRedrawing(redrawing: Boolean) {
    }

    public override fun onPause() {
        servoView.onPause()
        super.onPause()
    }

    public override fun onResume() {
        servoView.onResume()
        super.onResume()
    }

    override fun onMediaSessionMetadata(title: String, artist: String, album: String) {
        Log.d("onMediaSessionMetadata", "$title $artist $album")
        val mediaSession = mediaSession ?: MediaSession(servoView, applicationContext).also { mediaSession = it }
        mediaSession.updateMetadata(title, artist, album)
    }

    override fun onMediaSessionPlaybackStateChange(state: Int) {
        Log.d("onMediaSessionPlaybackStateChange", state.toString())
        val mediaSession = mediaSession ?: MediaSession(servoView, applicationContext).also { mediaSession = it }

        mediaSession.setPlaybackState(state)

        if (state == MediaSession.PLAYBACK_STATE_NONE) {
            mediaSession.hideMediaSessionControls()
            return
        }
        if (state == MediaSession.PLAYBACK_STATE_PLAYING ||
            state == MediaSession.PLAYBACK_STATE_PAUSED
        ) {
            mediaSession.showMediaSessionControls()
        }
    }

    override fun onMediaSessionSetPositionState(duration: Float, position: Float, playbackRate: Float) {
        Log.d("onMediaSessionSetPositionState", "$duration $position $playbackRate")
    }
}
