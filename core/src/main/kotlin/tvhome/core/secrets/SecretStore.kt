package tvhome.core.secrets

enum class SecretKey { CALENDAR_URL, ANTHROPIC_API_KEY }

interface SecretStore {
    fun get(k: SecretKey): String?
    fun put(k: SecretKey, v: String)
    fun clear(k: SecretKey)
}

/** In-memory fake for tests and previews. Its toString never shows values. */
class InMemorySecretStore(initial: Map<SecretKey, String> = emptyMap()) : SecretStore {
    private val values = HashMap<SecretKey, String>(initial)

    @Synchronized override fun get(k: SecretKey): String? = values[k]
    @Synchronized override fun put(k: SecretKey, v: String) { values[k] = v }
    @Synchronized override fun clear(k: SecretKey) { values.remove(k) }

    override fun toString(): String = "InMemorySecretStore"
}
