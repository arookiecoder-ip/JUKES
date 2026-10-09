package `in`.synthora.musicbox.database

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** JVM storage checks only: no UI, device or emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class DownloadManifestDatabaseTest {
    @Test fun manifestsAndCollectionMembershipSurviveReopenAndMissingFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "manifest-restart-test.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, DownloadManifestDatabase::class.java, name).build()
        try {
            val rows = listOf(DownloadManifestRow("completed", "song", "complete", "track"),
                DownloadManifestRow("collection", "album:one", "complete", "album metadata"),
                DownloadManifestRow("member:album:one", "0", "member", "song"))
            database.manifests().replace(rows)
            assertEquals(1, database.manifests().migrated())
            database.close()
            database = Room.databaseBuilder(context, DownloadManifestDatabase::class.java, name).build()
            assertEquals(rows.toSet(), database.manifests().read().toSet())
            val missing = rows.drop(1) + DownloadManifestRow("unavailable", "song", "missing", "track")
            database.manifests().replace(missing)
            assertEquals(missing.toSet(), database.manifests().read().toSet())
            // Removing the file never removes collection membership; re-download joins its album.
            database.manifests().replace(rows)
            assertEquals(rows.toSet(), database.manifests().read().toSet())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun failedReplacementRollsBackDeletesAndDoesNotMarkMigrationComplete() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DownloadManifestDatabase::class.java).build()
        try {
            val dao = database.manifests()
            val original = DownloadManifestRow("completed", "saved", "complete", "original")
            dao.insert(listOf(original))
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_failure BEFORE INSERT ON download_manifest WHEN NEW.`key` = 'failure' BEGIN SELECT RAISE(ABORT, 'storage failure'); END")
            try {
                dao.replace(listOf(DownloadManifestRow("queued", "failure", "queued", "new")))
                fail("Expected transaction failure")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(listOf(original), dao.read())
            assertEquals(0, dao.migrated())
        } finally { database.close() }
    }
}
