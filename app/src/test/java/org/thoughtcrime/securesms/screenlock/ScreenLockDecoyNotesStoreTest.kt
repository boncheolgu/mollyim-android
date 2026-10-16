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
    assertTrue(runCatching { store.readOrInitializeSamples() }.isFailure)
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

  @Test fun samplesArePersistedOnceAndEditsSurviveInitialization() {
    val store = DecoyNotesStore(directory.root)
    val samples = store.readOrInitializeSamples()
    assertEquals(listOf("먹어볼 와인", "분당 맛집 리스트", "중국 술 추천 리스트", "자전거 여행지"), samples.map { it.title })
    assertEquals(samples, DecoyNotesStore(directory.root).readOrInitializeSamples())
    assertEquals(10, samples[0].body.lines().size)
    assertEquals(10, samples[2].body.lines().size)
    val updated = samples.first().copy(body = "내가 수정한 목록", updatedAt = samples.first().updatedAt + 1)
    store.save(updated)
    assertEquals(updated, DecoyNotesStore(directory.root).readOrInitializeSamples().first())
  }

  @Test fun deletingAllSamplesDoesNotRepopulateTheList() {
    val store = DecoyNotesStore(directory.root)
    store.readOrInitializeSamples().forEach { store.delete(it.id) }
    assertTrue(DecoyNotesStore(directory.root).readOrInitializeSamples().isEmpty())
  }

  @Test fun existingNotesAndExistingEmptyListsArePreserved() {
    val store = DecoyNotesStore(directory.root)
    val note = DecoyNotesStore.Note("mine", "내 메모", "기존 내용", 1)
    store.save(note)
    assertEquals(listOf(note), store.readOrInitializeSamples())
    store.delete(note.id)
    assertTrue(DecoyNotesStore(directory.root).readOrInitializeSamples().isEmpty())
  }

  @Test fun backupIsRecoveredInsteadOfReplacedWithSamples() {
    val store = DecoyNotesStore(directory.root)
    val note = DecoyNotesStore.Note("mine", "내 메모", "복구할 내용", 1)
    store.save(note)
    val base = File(directory.root, "decoy-notes.json")
    assertTrue(base.renameTo(File(directory.root, "decoy-notes.json.bak")))
    assertEquals(listOf(note), DecoyNotesStore(directory.root).readOrInitializeSamples())
  }
}
