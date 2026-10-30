package com.nodecount.tvlauncher

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

const val SERVER_PORT = 8080
private const val MAX_BODY = 64 * 1024
private const val MAX_FAILS = 5
private const val LOCK_MS = 60_000L

// Mini serveur HTTP du réseau local : une page (protégée par un code PIN) pour éditer les mots doux.
class MessageServer(
    private val pin: () -> String,
    private val load: () -> String,
    private val isCustom: () -> Boolean,
    private val save: (String) -> Unit,
    private val reset: () -> Unit,
    private val onVisit: (String) -> Unit,
    private val onUnlock: () -> Unit,
) {
    @Volatile private var socket: ServerSocket? = null
    private var fails = 0
    private var lockedUntil = 0L

    fun start() {
        if (socket != null) return
        thread(isDaemon = true, name = "message-server") {
            val server = try { ServerSocket(SERVER_PORT) } catch (e: Exception) { return@thread }
            socket = server
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 5000
                val input = c.getInputStream()
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines[0].split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = HashMap<String, String>()
                for (l in lines.drop(1)) {
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val out = c.getOutputStream()
                when {
                    method == "GET" && path == "/" -> {
                        onVisit(c.inetAddress?.hostAddress ?: "?")
                        reply(out, 200, "text/html", PAGE)
                    }
                    path == "/m" && (method == "GET" || method == "POST") -> {
                        val body = if (method == "POST") readBody(input, headers) else ""
                        api(out, method, headers["x-pin"] ?: "", body)
                    }
                    else -> reply(out, 404, "text/plain", "Introuvable")
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun api(out: java.io.OutputStream, method: String, given: String, body: String) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now < lockedUntil) { reply(out, 429, "application/json", "{\"error\":\"locked\"}"); return }
            if (!sameText(given, pin())) {
                if (++fails >= MAX_FAILS) { fails = 0; lockedUntil = now + LOCK_MS }
                reply(out, 403, "application/json", "{\"error\":\"pin\"}")
                return
            }
            fails = 0
        }
        if (method == "GET") onUnlock()
        if (method == "POST") {
            val request = JSONObject(body)
            if (request.optBoolean("reset")) reset() else save(request.getString("text"))
        }
        val answer = JSONObject().put("text", load()).put("custom", isCustom())
        reply(out, 200, "application/json", answer.toString())
    }

    private fun sameText(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString("UTF-8").trimEnd()
        }
        return null
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_BODY) throw IllegalArgumentException("taille")
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(data, 0, read, Charsets.UTF_8)
    }

    private fun reply(out: java.io.OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Too Many Requests" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        // Adresse IPv4 locale de la TV (Wi-Fi ou Ethernet), pour afficher l'URL à saisir.
        fun localAddress(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
                    ?.hostAddress
            } catch (e: Exception) {
                null
            }
        }

        private val PAGE = """<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Mots doux</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#2a0a1f;color:#fff0f7;display:flex;justify-content:center}
main{width:min(640px,92vw);padding:24px 0}
h1{font-size:28px;margin:0 0 4px}p{color:#f3bcd7;margin:0 0 16px}
input,textarea,button{font:inherit;border-radius:10px;border:1px solid #ffd0e6;background:#3d1030;color:#fff0f7;padding:10px 12px;box-sizing:border-box}
.row{display:flex;gap:8px;margin-bottom:10px}.row input{flex:1;min-width:0}
.row button.x{padding:10px 14px}
button{background:#ff7ac0;color:#2a0a1f;font-weight:700;border:0;cursor:pointer;padding:10px 20px}
button.alt{background:#3d1030;color:#fff0f7;border:1px solid #ffd0e6;font-weight:400}
#err{color:#ffb3b3;min-height:1.4em}#ok{color:#9ff0c0}
</style></head><body><main>
<h1>Diffuse un mot doux à la télévision</h1>
<div id="lock"><p>Code PIN affiché sur la TV :</p>
<input id="pin" inputmode="numeric" maxlength="8" autocomplete="off" autofocus> <button id="go">Ouvrir</button></div>
<div id="edit" hidden><div id="rows"></div>
<p><button class="alt" id="add">+ Ajouter un mot doux</button></p>
<p><button id="save">Enregistrer</button> <span id="ok"></span></p>
<p><button class="alt" id="reset">Réinitialiser</button></p></div>
<div id="err"></div>
<script>
var pin='';
function $(i){return document.getElementById(i)}
function call(method,body){return fetch('/m',{method:method,headers:{'X-Pin':pin},body:body}).then(function(r){
 if(r.status===403)throw 'Mauvais code PIN.';if(r.status===429)throw 'Trop d\'essais : réessaie dans une minute.';
 if(!r.ok)throw 'Erreur '+r.status;return r.json()})}
function fail(e){$('err').textContent=typeof e==='string'?e:'Connexion impossible.'}
$('go').onclick=function(){pin=$('pin').value.trim();$('err').textContent='';
 call('GET').then(show).catch(fail)};
function row(text,focus){var r=document.createElement('div');r.className='row';
 var i=document.createElement('input');i.value=text;i.maxLength=200;i.placeholder='Un mot doux…';
 i.onkeydown=function(e){if(e.key==='Enter'){e.preventDefault();row('',true)}};
 var x=document.createElement('button');x.className='alt x';x.textContent='✕';x.title='Retirer';
 x.onclick=function(){r.remove()};
 r.appendChild(i);r.appendChild(x);$('rows').appendChild(r);if(focus)i.focus()}
function lines(){return Array.prototype.map.call($('rows').querySelectorAll('input'),function(i){return i.value.trim()})
 .filter(function(l){return l}).join('\n')}
function show(j){$('lock').hidden=true;$('edit').hidden=false;$('rows').innerHTML='';
 var l=j.text.split('\n').map(function(s){return s.trim()}).filter(function(s){return s});
 (l.length?l:['']).forEach(function(s){row(s)});
 $('reset').disabled=!j.custom}
$('add').onclick=function(){row('',true)};
$('pin').onkeydown=function(e){if(e.key==='Enter')$('go').onclick()};
function post(data,msg){$('err').textContent='';$('ok').textContent='';
 call('POST',JSON.stringify(data)).then(function(j){show(j);$('ok').textContent=msg}).catch(fail)}
$('save').onclick=function(){post({text:lines()},'Enregistré, la TV est à jour.')};
$('reset').onclick=function(){if(!confirm('Supprimer ta liste de mots doux et revenir à la liste par défaut ?'))return;post({reset:true},'Liste personnalisée supprimée : la TV repart sur la liste par défaut.')};
</script></main></body></html>"""
    }
}
