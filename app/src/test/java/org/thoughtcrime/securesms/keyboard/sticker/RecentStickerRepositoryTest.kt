package org.thoughtcrime.securesms.keyboard.sticker

import android.app.Application
import android.database.MatrixCursor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.EmojiSearchTable
import org.thoughtcrime.securesms.database.StickerTable
import org.thoughtcrime.securesms.testutil.MockSignalStoreRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class RecentStickerRepositoryTest {
  @get:Rule val signalStore = MockSignalStoreRule()

  @Test fun keyboardReadsTheCurrentLimitOnEveryRefresh() {
    val stickers = mockk<StickerTable>()
    every { signalStore.settings.recentStickerLimit } returnsMany listOf(24, 96)
    every { stickers.getInstalledStickerPacks() } answers { MatrixCursor(arrayOf("_id")) }
    every { stickers.getRecentlyUsedStickers(any()) } answers { MatrixCursor(arrayOf("_id")) }
    val repository = StickerKeyboardRepository(stickers)

    repeat(2) {
      val finished = CountDownLatch(1)
      repository.getStickerPacks { finished.countDown() }
      assertTrue(finished.await(10, TimeUnit.SECONDS))
    }

    verify(exactly = 1) { stickers.getRecentlyUsedStickers(24) }
    verify(exactly = 1) { stickers.getRecentlyUsedStickers(96) }
  }

  @Test fun emptySearchReadsTheCurrentLimitOnEveryQuery() {
    val stickers = mockk<StickerTable>()
    val emojiSearch = mockk<EmojiSearchTable>()
    every { signalStore.settings.recentStickerLimit } returnsMany listOf(24, 200)
    every { stickers.getRecentlyUsedStickers(any()) } answers { MatrixCursor(arrayOf("_id")) }
    val repository = StickerSearchRepository(emojiSearch, stickers)

    assertTrue(repository.search("").isEmpty())
    assertTrue(repository.search("").isEmpty())

    verify(exactly = 1) { stickers.getRecentlyUsedStickers(24) }
    verify(exactly = 1) { stickers.getRecentlyUsedStickers(200) }
    verify(exactly = 0) { emojiSearch.query(any(), any()) }
  }
}
