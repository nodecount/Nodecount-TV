package com.nodecount.tvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private const val ICON_W = 320
private const val ICON_H = 180

// Applis de contenu affichées par défaut, même quand elles sont préinstallées (donc "système").
// Les applis installées par l'utilisateur sont aussi affichées par défaut (sauf Projectivy).
private val CONTENT_APPS = setOf(
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.disney.disneyplus",
    "tv.twitch.android.app",
    "com.google.android.youtube.tv",
    "com.google.android.youtube.tvmusic",
    "com.spotify.tv.android",
    "com.plexapp.android",
    "org.xbmc.kodi",
    "org.videolan.vlc",
    "com.canal.android.canal",
    "fr.francetv.pluzz",
    "tv.arte.plus7",
    "fr.m6.m6replay",
    "com.apple.atve.androidtv.appletv",
    "com.wbd.stream",
    "com.cbs.ott",
    "com.crunchyroll.crunchyroid",
    "tv.molotov.app",
)
private const val PROJECTIVY = "com.spocky.projengmenu"

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var messageServer: MessageServer

    private val prefs get() = getSharedPreferences("launcher", Context.MODE_PRIVATE)

    // Code PIN de la page d'édition des mots doux : 4 chiffres tirés au premier lancement, puis conservés.
    private fun serverPin(): String =
        prefs.getString("pin", null) ?: "%04d".format(java.security.SecureRandom().nextInt(10000))
            .also { prefs.edit().putString("pin", it).apply() }

    // Mots doux : si un fichier personnalisé existe sur la TV, on ne lit que lui ; sinon la liste par défaut (assets).
    private val customFile get() = java.io.File(filesDir, "messages-custom.txt")

    private fun defaultMessages(): String =
        assets.open("messages.txt").bufferedReader().use { it.readText() }

    private fun loadMessages(): String =
        customFile.let { if (it.exists()) it.readText() else defaultMessages() }

    private fun refreshMessage() =
        runOnUiThread { webView.evaluateJavascript("if (window.onMessagesChanged) onMessagesChanged();", null) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        messageServer = MessageServer(
            pin = { serverPin() },
            load = { loadMessages() },
            isCustom = { customFile.exists() },
            save = { text -> customFile.writeText(text); refreshMessage() },
            reset = { customFile.delete(); refreshMessage() },
            onVisit = { ip ->
                val js = "if (window.showConnection) showConnection(${JSONObject.quote(ip)}, ${JSONObject.quote(serverPin())});"
                runOnUiThread { webView.evaluateJavascript(js, null) }
            },
            onUnlock = { runOnUiThread { webView.evaluateJavascript("if (window.hideConnection) hideConnection();", null) } },
        )
        messageServer.start()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/icons/", IconPathHandler())
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Les console.log de la page arrivent dans logcat (tag "NodecountJS"), utile pour diagnostiquer sur la TV.
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i("NodecountJS", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(Bridge(), "Android")
        }
        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onDestroy() {
        messageServer.stop()
        super.onDestroy()
    }

    // Le launcher passe en arrière-plan (appli lancée, écran de veille) : la musique du thème s'arrête.
    override fun onPause() {
        super.onPause()
        webView.evaluateJavascript("if (window.onLauncherPause) onLauncherPause();", null)
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("if (window.refresh) refresh();", null)
    }

    // Touches de volume : on règle le son nous-mêmes (sans la barre système) et la page affiche sa barre.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return super.onKeyDown(keyCode, event)
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        webView.evaluateJavascript("if (window.showVolume) showVolume($level, $max, $muted);", null)
        return true
    }

    // Un launcher ne se ferme pas avec Retour : la page décide (ferme le gestionnaire d'applis).
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript("if (window.onBack) onBack();", null)
    }

    inner class Bridge {
        // Liste des mots doux (un par ligne) et accès à la page d'édition : {"url": "...", "pin": "1234"}.
        @JavascriptInterface
        fun getMessages(): String = loadMessages()

        @JavascriptInterface
        fun getServerInfo(): String {
            val ip = MessageServer.localAddress()
            return JSONObject().put("url", if (ip == null) "" else "http://$ip:$SERVER_PORT").put("pin", serverPin()).toString()
        }

        // Choix de l'utilisateur : {"<package>": true|false} (true = affichée, false = masquée).
        @JavascriptInterface
        fun getOverrides(): String = prefs.getString("overrides", "{}") ?: "{}"

        @JavascriptInterface
        fun setOverrides(json: String) {
            prefs.edit().putString("overrides", json).apply()
        }

        // Disposition des vignettes du thème de base : {"mode":"scatter","seed":"..."} ou {"mode":"grid"}.
        @JavascriptInterface
        fun getLayout(): String = prefs.getString("layout", "{}") ?: "{}"

        @JavascriptInterface
        fun setLayout(json: String) {
            prefs.edit().putString("layout", json).apply()
        }

        @JavascriptInterface
        fun getTheme(): String = prefs.getString("theme", "glass") ?: "glass"

        @JavascriptInterface
        fun setTheme(name: String) {
            prefs.edit().putString("theme", name).apply()
        }

        // Réglages simples (clé -> texte), ex. "noanim" : "1" quand les animations sont coupées.
        @JavascriptInterface
        fun getSetting(key: String): String = prefs.getString("s_$key", "") ?: ""

        @JavascriptInterface
        fun setSetting(key: String, value: String) {
            prefs.edit().putString("s_$key", value).apply()
        }

        @JavascriptInterface
        fun getApps(): String {
            val pm = packageManager
            val seen = HashSet<String>()
            val apps = ArrayList<Triple<String, String, Boolean>>()
            for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                val query = Intent(Intent.ACTION_MAIN).addCategory(category)
                for (info in pm.queryIntentActivities(query, 0)) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == packageName || !seen.add(pkg)) continue
                    val isSystem = info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    val shownByDefault = pkg in CONTENT_APPS || (!isSystem && pkg != PROJECTIVY)
                    apps.add(Triple(pkg, info.loadLabel(pm).toString(), shownByDefault))
                }
            }
            apps.sortBy { it.second.lowercase() }
            val json = JSONArray()
            for ((pkg, label, shownByDefault) in apps) {
                json.put(
                    JSONObject().put("package", pkg).put("label", label).put("default", shownByDefault)
                )
            }
            return json.toString()
        }

        // Nettoyage des applis préinstallées : le scan est immédiat, l'application passe par l'ADB de la TV
        // (en tâche de fond) et rend le résultat à la page par onCleanDone.
        @JavascriptInterface
        fun scanBloat(): String = Cleaner(this@MainActivity).scan().toString()

        @JavascriptInterface
        fun cleanBloat(action: String, pkg: String) {
            Thread {
                val result = Cleaner(this@MainActivity).apply(action, pkg).toString()
                runOnUiThread { webView.evaluateJavascript("onCleanDone($result)", null) }
            }.start()
        }

        @JavascriptInterface
        fun launch(pkg: String) {
            val pm = packageManager
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
                ?: pm.getLaunchIntentForPackage(pkg)
                ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    // Sert https://appassets.androidplatform.net/icons/<package> : bannière TV, sinon icône.
    inner class IconPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            return try {
                val pm = packageManager
                val drawable = pm.getApplicationBanner(path) ?: pm.getApplicationIcon(path)
                val out = ByteArrayOutputStream()
                toBitmap(drawable).compress(Bitmap.CompressFormat.PNG, 100, out)
                WebResourceResponse("image/png", null, ByteArrayInputStream(out.toByteArray()))
            } catch (e: Exception) {
                null
            }
        }

        private fun toBitmap(d: Drawable): Bitmap {
            val bitmap = Bitmap.createBitmap(ICON_W, ICON_H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val ratio = d.intrinsicWidth.toFloat() / d.intrinsicHeight.coerceAtLeast(1)
            // Bannière 16:9 : plein cadre. Icône carrée : centrée.
            if (ratio > 1.3f) {
                d.setBounds(0, 0, ICON_W, ICON_H)
            } else {
                val size = ICON_H
                val left = (ICON_W - size) / 2
                d.setBounds(left, 0, left + size, size)
            }
            d.draw(canvas)
            return bitmap
        }
    }
}
