package dev.windplayer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol

object ServerStore {
    private const val TAG = "ServerStore"
    private const val PREFS = "windplayer_servers_encrypted"
    // H10: distinct filename for the plaintext fallback. If we reused PREFS,
    // a device that wrote plaintext (after a keystore failure) and later
    // recovered crypto would refuse to read the non-encrypted file and the
    // user would silently lose every saved server.
    private const val PREFS_PLAIN = "windplayer_servers_plain"

    /**
     * `true` once we have successfully opened the encrypted prefs.
     * Read by [MainActivity]/MobileApp to surface a security warning to the user
     * if encryption is unavailable on their device.
     */
    @Volatile
    var encryptionActive: Boolean = false
        private set

    // H15: EncryptedSharedPreferences.create() does keystore + file IO + key
    // derivation. Rebuilding it on every load/save/add/remove call causes
    // noticeable jank at startup and on every server edit. Cache one instance
    // per applicationContext (never per Activity, to avoid leaking the Activity).
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    private val cacheLock = Any()

    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(cacheLock) {
            cachedPrefs?.let { return it }
            val appContext = context.applicationContext
            val built = try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext, PREFS, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { encryptionActive = true }
            } catch (e: Exception) {
                // Log loudly — passwords will be stored in plaintext from now on.
                Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plaintext: ${e.message}")
                encryptionActive = false
                appContext.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE)
            }
            cachedPrefs = built
            return built
        }
    }

    fun load(context: Context): List<ServerConfig> {
        val p = prefs(context)
        val count = p.getInt("count", 0)
        return (0 until count).mapNotNull { i ->
            val id = p.getString("s${i}_id", null) ?: return@mapNotNull null
            ServerConfig(
                id = id,
                name = p.getString("s${i}_name", "") ?: "",
                protocol = try { VfsProtocol.valueOf(p.getString("s${i}_proto", "SFTP") ?: "SFTP") } catch (_: Exception) { VfsProtocol.SFTP },
                host = p.getString("s${i}_host", "") ?: "",
                port = p.getString("s${i}_port", "0")?.toIntOrNull() ?: 0,
                username = p.getString("s${i}_user", "") ?: "",
                password = p.getString("s${i}_pass", "") ?: "",
                basePath = p.getString("s${i}_path", "/") ?: "/",
                useTls = p.getBoolean("s${i}_tls", false)
            )
        }
    }

    fun save(context: Context, servers: List<ServerConfig>) {
        val p = prefs(context)
        val oldCount = p.getInt("count", 0)
        val e = p.edit()
        e.putInt("count", servers.size)
        servers.forEachIndexed { i, s ->
            e.putString("s${i}_id", s.id)
            e.putString("s${i}_name", s.name)
            e.putString("s${i}_proto", s.protocol.name)
            e.putString("s${i}_host", s.host)
            e.putString("s${i}_port", s.port.toString())
            e.putString("s${i}_user", s.username)
            e.putString("s${i}_pass", s.password)
            e.putString("s${i}_path", s.basePath)
            e.putBoolean("s${i}_tls", s.useTls)
        }
        for (i in servers.size until oldCount) {
            listOf("id","name","proto","host","port","user","pass","path","tls").forEach { f -> e.remove("s${i}_$f") }
        }
        e.apply()
    }

    fun add(context: Context, server: ServerConfig): List<ServerConfig> {
        val list = load(context); val updated = list + server; save(context, updated); return updated
    }

    fun remove(context: Context, id: String): List<ServerConfig> {
        val list = load(context); val updated = list.filterNot { it.id == id }; save(context, updated); return updated
    }
}
