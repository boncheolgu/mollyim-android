package org.thoughtcrime.securesms.screenlock

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.decoynotes.DecoyNotesStore
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class ScreenLockDecoyNotesStoreTest {
  @get:Rule val directory = TemporaryFolder()

  @Test fun notesSurviveReopeningAndEditingDoesNotReplaceOtherNotes() {
    val store = DecoyNotesStore(directory.root)
    val first = DecoyNotesStore.Note("first", "장보기", "우유\n빵 🍞", 1)
    val second = DecoyNotesStore.Note("second", "Travel", "Check tickets", 2)
    store.save(first)
    store.save(second)
    val updated = first.copy(body = "우유\n빵 🍞\n사과", updatedAt = 3)
    store.save(updated)
    assertEquals(listOf(updated, second), DecoyNotesStore(directory.root).read())
    store.delete(first.id)
    assertEquals(listOf(second), DecoyNotesStore(directory.root).read())
  }

  @Test fun damagedStorageIsNotSilentlyOverwrittenBySaveOrDelete() {
    val file = File(directory.root, "decoy-notes.json").apply { writeText("damaged notes") }
    val store = DecoyNotesStore(directory.root)
    assertTrue(runCatching { store.read() }.isFailure)
    assertTrue(runCatching { store.save(DecoyNotesStore.Note("new", "Title", "Body", 1)) }.isFailure)
    assertTrue(runCatching { store.delete("new") }.isFailure)
    assertEquals("damaged notes", file.readText())
  }

  @Test fun rejectedOversizedEditPreservesTheSavedContent() {
    val store = DecoyNotesStore(directory.root)
    val note = DecoyNotesStore.Note("note", "Title", "Saved content", 1)
    store.save(note)
    assertTrue(runCatching { store.save(note.copy(body = "a".repeat(DecoyNotesStore.MAX_BODY + 1))) }.isFailure)
    assertEquals(listOf(note), store.read())
  }
}
