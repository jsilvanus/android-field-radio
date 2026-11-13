package fieldradio.library

import kotlinx.coroutines.flow.Flow

/**
 * Library model: a collection of audio files for playback during broadcast.
 * MVP: in-process DAO with a list of files (imported via SAF).
 * Post-MVP: Room database with search, metadata (title, artist, duration).
 */

data class LibraryItem(
    val id: String,
    val title: String,
    val uri: String, // content:// or file:// URI
    val durationMs: Long? = null,
    val mimeType: String? = null,
)

interface LibraryDao {
    suspend fun getAll(): List<LibraryItem>
    fun getAsFlow(): Flow<List<LibraryItem>>
    suspend fun add(item: LibraryItem)
    suspend fun remove(id: String)
    suspend fun clear()
}

/** Noop implementation for MVP (no persistence). */
class InMemoryLibraryDao : LibraryDao {
    private val items = mutableListOf<LibraryItem>()

    override suspend fun getAll() = items.toList()
    override fun getAsFlow() = kotlinx.coroutines.flow.flowOf(items.toList())
    override suspend fun add(item: LibraryItem) { items.add(item) }
    override suspend fun remove(id: String) { items.removeAll { it.id == id } }
    override suspend fun clear() { items.clear() }
}

/**
 * Library importer: SAF (Storage Access Framework) file picker → LibraryDao.
 * Android-only; not in JVM tests.
 */
interface LibraryImporter {
    /**
     * Import from a single URI (returned by SAF picker).
     * Should validate mime type, extract metadata (if available).
     */
    suspend fun importUri(uri: String): LibraryItem?

    /**
     * Bulk import from a folder (SAF tree URI).
     */
    suspend fun importFolder(uri: String): List<LibraryItem>
}
