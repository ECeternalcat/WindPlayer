package dev.windplayer

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol

object ServerStore {
    private const val PREFS = "windplayer_servers_encrypted"

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
                basePath = p.getString("s${i}_path", "/") ?: "/"
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
        }
        for (i in servers.size until oldCount) {
            listOf("id","name","proto","host","port","user","pass","path").forEach { f -> e.remove("s${i}_$f") }
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
