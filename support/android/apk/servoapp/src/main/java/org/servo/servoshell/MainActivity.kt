/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell

import android.app.AlertDialog
import android.content.Context
import android.content.res.AssetManager
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
import java.io.File
import java.io.FileNotFoundException

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
        // post_build_commands.py's `_bundle_android`), if anything was bundled at all -- via a
        // real, extracted-once filesystem path, not `file:///android_asset/...` (see
        // `extractBundledContent`'s own doc comment for why that never actually worked). A
        // plain engine-shell build with no bundled content (e.g. .github/workflows/
        // android.yml's per-commit build) extracts an empty `www/` tree, so this still 404s
        // inside Servo itself the same as before -- same as any other missing local file, no
        // special-casing needed here.
        val contentDir = extractBundledContent(this, "www", File(filesDir, "www"))
        servoView.loadUri("file://${File(contentDir, "index.html").absolutePath}")
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

/**
 * Copies the `assets/<assetRoot>/` tree Gradle bundles the game's own content into (see
 * `servoapp/build.gradle.kts`/`post_build_commands.py`'s `_bundle_android`) out to a real,
 * plain filesystem directory the first time this exact app build runs.
 *
 * This is necessary, not just a nice-to-have: Servo's own `file://` protocol handler
 * (`ports/servoshell/desktop/protocols/file.rs`) is a plain `std::fs::File::open` -- it has no
 * concept of Android's `android_asset` virtual path, a WebView-specific convention only
 * Chromium's own asset resolver understands. `file:///android_asset/www/index.html` (this
 * function's own predecessor) could therefore never resolve, with or without real bundled
 * content -- confirmed directly against a real device: "Could not load the requested page:
 * Opening file failed" on a build with real, verified-present bundled assets. Extracting once
 * to `filesDir` (always private and writable, no runtime permission needed, unlike external
 * storage) and loading a real `file://` path from there sidesteps the missing Android-asset
 * support entirely, the same "extract once, then load a real path" shape the desktop shell's
 * own packed-content cache already uses (see the engine's own `CUSTOMIZATIONS.md`, "Pack
 * --content-dir into the APK" and "Single-executable bundle" entries) -- just simpler here,
 * since Android's own `mach bundle` path has no compression step to reverse, only a plain
 * asset-to-file copy.
 *
 * Skips the copy on a later launch of the *same* installed build (tracked via a marker file
 * storing the app's own `longVersionCode`) -- an update (a new APK, a new `versionCode`) still
 * re-extracts, so stale content from a previous install never lingers.
 */
private fun extractBundledContent(context: Context, assetRoot: String, destDir: File): File {
    val marker = File(destDir.parentFile, "${destDir.name}.extracted-version")
    val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
    if (marker.isFile && marker.readText() == versionCode && destDir.isDirectory) {
        return destDir
    }
    destDir.deleteRecursively()
    copyAssetTree(context.assets, assetRoot, destDir)
    marker.parentFile?.mkdirs()
    marker.writeText(versionCode)
    return destDir
}

/**
 * Recursively copies one asset path into `destFile`. `AssetManager.list()`'s own return value
 * for a *leaf* file (as opposed to a directory) isn't reliably documented across Android
 * versions (empty array on some, an exception on others) -- trying `open()` first and treating
 * a `FileNotFoundException` as "this was a directory, not a file" is the robust way to tell
 * the two apart regardless.
 */
private fun copyAssetTree(assets: AssetManager, assetPath: String, destFile: File) {
    try {
        assets.open(assetPath).use { input ->
            destFile.parentFile?.mkdirs()
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (e: FileNotFoundException) {
        destFile.mkdirs()
        for (child in assets.list(assetPath) ?: emptyArray()) {
            copyAssetTree(assets, "$assetPath/$child", File(destFile, child))
        }
    }
}
