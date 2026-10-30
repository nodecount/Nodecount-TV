package com.nodecount.tvlauncher

import android.content.Context
import android.content.pm.PackageManager
import dadb.AdbKeyPair
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Nettoyage des applis préinstallées (constructeur, télémétrie...).
 *
 * Une appli Android ne peut pas désactiver les autres : c'est réservé au « shell » ADB. Le nettoyage se connecte
 * donc à l'ADB de la TV elle-même (localhost:5555), ce qui demande que le débogage réseau soit activé et qu'on
 * accepte une fois la clé de l'appli sur l'écran. La liste des paquets connus est dans assets/bloat.json.
 */
class Cleaner(private val context: Context) {

    private fun catalogue(): JSONArray =
        JSONArray(context.assets.open("bloat.json").bufferedReader().use { it.readText() })

    /** Les paquets du catalogue présents sur cette TV, avec leur état (« active » ou « disabled »). */
    fun scan(): JSONArray {
        val pm = context.packageManager
        val found = JSONArray()
        val list = catalogue()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val info = try {
                pm.getApplicationInfo(item.getString("package"), PackageManager.MATCH_DISABLED_COMPONENTS)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
            found.put(JSONObject(item.toString()).put("state", if (info.enabled) "active" else "disabled"))
        }
        return found
    }

    /**
     * Désactive (action « disable ») les paquets actifs du scan, ou réactive (« enable ») ceux qui sont désactivés.
     * Avec `pkg` non vide, ne touche qu'à ce paquet (il doit être dans le catalogue).
     */
    fun apply(action: String, pkg: String = ""): JSONObject {
        val wanted = if (action == "disable") "active" else "disabled"
        val targets = ArrayList<String>()
        val scanned = scan()
        for (i in 0 until scanned.length()) {
            val item = scanned.getJSONObject(i)
            val name = item.getString("package")
            if (item.getString("state") == wanted && (pkg.isEmpty() || pkg == name)) targets.add(name)
        }
        val result = JSONObject().put("action", action).put("done", 0).put("failed", JSONArray())
        if (targets.isEmpty()) return result
        try {
            val priv = File(context.filesDir, "adbkey")
            val pub = File(context.filesDir, "adbkey.pub")
            if (!priv.exists()) AdbKeyPair.generate(priv, pub)
            Dadb.create("localhost", 5555, AdbKeyPair.read(priv, pub)).use { adb ->
                var done = 0
                for (pkg in targets) {
                    val cmd = if (action == "disable") "pm disable-user --user 0 $pkg" else "pm enable $pkg"
                    val out = adb.shell(cmd)
                    if (out.exitCode == 0) done++ else result.getJSONArray("failed").put(pkg)
                }
                result.put("done", done)
            }
        } catch (e: Exception) {
            result.put("error", e.message ?: e.javaClass.simpleName)
        }
        return result
    }
}
