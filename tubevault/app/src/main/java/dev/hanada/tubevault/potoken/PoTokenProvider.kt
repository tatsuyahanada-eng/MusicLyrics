package dev.hanada.tubevault.potoken

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A minted token together with the visitor id it is bound to. */
data class PoToken(
    val visitorData: String,
    val token: String,
)

/**
 * Hands out PO Tokens, keeping the expensive part off the hot path.
 *
 * Running the BotGuard challenge takes seconds, but the resulting session can
 * mint many tokens, and each token is good for roughly ten minutes. So the
 * session is created once and only the cheap minting repeats. The mutex
 * matters because several downloads can ask at the same moment and the
 * attestation must not run concurrently.
 */
class PoTokenProvider(private val appContext: Context) {

    private val mutex = Mutex()

    private var session: PoTokenWebView? = null
    private var cached: PoToken? = null
    private var mintedAt = 0L

    @Volatile
    var lastError: String? = null
        private set

    /** The current token, minting a new one when the last has aged out. */
    suspend fun current(): PoToken? = mutex.withLock {
        val existing = cached
        if (existing != null && System.currentTimeMillis() - mintedAt < TOKEN_TTL_MS) {
            return@withLock existing
        }
        refresh()
    }

    /** Forces a fresh attestation; used by the diagnostic in settings. */
    suspend fun probe(): Result<PoToken> = mutex.withLock {
        discardSession()
        val token = refresh()
        if (token != null) {
            Result.success(token)
        } else {
            Result.failure(PoTokenException(lastError ?: "unknown failure"))
        }
    }

    fun shutdown() {
        discardSession()
    }

    private suspend fun refresh(): PoToken? {
        val active = session ?: PoTokenWebView(appContext).also { session = it }

        val visitorData = try {
            active.start()
        } catch (e: Exception) {
            // A failed attestation poisons the session permanently, so drop it
            // rather than retrying against a dead WebView.
            Log.w(TAG, "attestation failed", e)
            lastError = e.message ?: "attestation failed"
            discardSession()
            return null
        }

        return try {
            val token = active.mint(visitorData)
            lastError = null
            mintedAt = System.currentTimeMillis()
            PoToken(visitorData, token).also { cached = it }
        } catch (e: Exception) {
            Log.w(TAG, "minting failed", e)
            lastError = e.message ?: "minting failed"
            discardSession()
            null
        }
    }

    private fun discardSession() {
        session?.destroy()
        session = null
        cached = null
        mintedAt = 0L
    }

    private companion object {
        const val TAG = "PoTokenProvider"

        /** Tokens last about ten minutes; re-mint well before that. */
        const val TOKEN_TTL_MS = 5L * 60 * 1000
    }
}
