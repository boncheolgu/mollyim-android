package org.thoughtcrime.securesms.screenlock

import android.app.Application
import android.content.Context
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.signal.core.util.contentproviders.BlobProvider
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class ScreenLockFailureBlobCleanupTest {
  @Test fun deletesConversationBlobsButKeepsBackupsAndStickerPacks() {
    val context = RuntimeEnvironment.getApplication<Application>()
    val provider = BlobProvider(context, mockk())
    val deleted = listOf("single_session_blobs", "multi_session_blobs", "draft_blobs").map {
      File(context.getDir(it, Context.MODE_PRIVATE), "sample").apply { writeText("local conversation") }
    }
    val retained = listOf("temp_backups", "stickers").map {
      File(context.getDir(it, Context.MODE_PRIVATE), "sample").apply { writeText("keep") }
    }
    provider.clearConversationBlobs(context)
    assertTrue(deleted.none { it.exists() })
    assertTrue(retained.all { it.readText() == "keep" })
    provider.clearConversationBlobs(context) // Interrupted cleanup can safely repeat this step.
  }
}
