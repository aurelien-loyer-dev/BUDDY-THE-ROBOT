package com.example.buddyug

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException

/**
 * Serveur HTTP + WebSocket embarqué pour l'écran de diffusion du salon.
 *
 * Sert la page React statique (`assets/web/`) et diffuse en direct l'état
 * de la conversation (écoute / réflexion / parole, texte entendu, réponse,
 * marqueur scanné) à tout navigateur connecté sur `http://<ip_buddy>:PORT/`.
 * Aucune dépendance externe : l'écran n'a besoin que d'un navigateur pointé
 * sur l'IP du robot, tout est servi depuis l'app elle-même.
 */
class StateServer(private val context: Context, port: Int) : NanoWSD(port) {

    private val clients = mutableSetOf<ClientSocket>()
    private var lastState: JSONObject = JSONObject().apply {
        put("state", "idle")
        put("heard", JSONObject.NULL)
        put("response", JSONObject.NULL)
        put("marker", JSONObject.NULL)
    }

    fun broadcast(update: JSONObject) {
        lastState = update
        val text = update.toString()
        synchronized(clients) {
            clients.toList().forEach {
                try {
                    it.send(text)
                } catch (e: IOException) {
                    clients.remove(it)
                }
            }
        }
    }

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): WebSocket = ClientSocket(handshake)

    override fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        var uri = session.uri
        if (uri.isBlank() || uri == "/") uri = "/index.html"
        val assetPath = "web$uri"
        return try {
            val stream = context.assets.open(assetPath)
            newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeFor(uri), stream)
        } catch (e: IOException) {
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "text/plain",
                "404 Not Found: $uri (page React pas encore buildée dans assets/web ?)"
            )
        }
    }

    private fun mimeFor(uri: String): String = when {
        uri.endsWith(".html") -> "text/html"
        uri.endsWith(".js") -> "application/javascript"
        uri.endsWith(".css") -> "text/css"
        uri.endsWith(".json") -> "application/json"
        uri.endsWith(".svg") -> "image/svg+xml"
        uri.endsWith(".png") -> "image/png"
        uri.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private inner class ClientSocket(handshake: NanoHTTPD.IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            synchronized(clients) { clients.add(this) }
            try {
                send(lastState.toString())
            } catch (e: IOException) {
                // Le client s'est déconnecté avant même de recevoir l'état initial.
            }
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            synchronized(clients) { clients.remove(this) }
        }

        override fun onMessage(message: WebSocketFrame) {
            // L'écran n'envoie rien, on ignore.
        }

        override fun onPong(pong: WebSocketFrame) {}

        override fun onException(exception: IOException) {
            synchronized(clients) { clients.remove(this) }
        }
    }
}
