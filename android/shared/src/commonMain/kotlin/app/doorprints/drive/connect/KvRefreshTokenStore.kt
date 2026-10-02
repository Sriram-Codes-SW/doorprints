package app.doorprints.drive.connect

/**
 * The refresh token in a *sealed* [KeyValueStore] (Android: Keystore-sealed preferences that die with the screen lock;
 * the iPhone's Keychain item when its store exists). Reads and writes that cannot happen say [TokenStoreException],
 * never return a wrong value.
 */
class KvRefreshTokenStore(private val sealed: KeyValueStore) : RefreshTokenStore {
    override fun load(): String? = sealed.get(KEY)
    override fun save(token: String) = sealed.put(KEY, token)
    override fun clear() = sealed.remove(KEY)

    private companion object {
        const val KEY = "drive.refresh-token"
    }
}
