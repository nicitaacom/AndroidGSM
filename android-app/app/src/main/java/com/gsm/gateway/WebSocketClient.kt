import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class WebSocketClient(private val service: MyForegroundService) {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    fun connect(url: String) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("WebSocket", "Opened")
            // TODO: Implement logic for when WebSocket opens
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("WebSocket", "Message received: $text")
            // TODO: Handle incoming messages like CALL_START, CALL_END, SEND_DTMF
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d("WebSocket", "Message received: $bytes")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            Log.d("WebSocket", "Closing: $code / $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("WebSocket", "Error: ${t.message}")
            // TODO: Implement reconnect logic
        }
    }

    fun sendMessage(message: String) {
        webSocket?.send(message)
    }

    fun disconnect() {
        webSocket?.close(1000, null)
    }
}
