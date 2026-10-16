package org.thoughtcrime.securesms.decoynotes

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/** Independent, device-local notes. No chat database, account or authentication dependency. */
internal class DecoyNotesStore(directory: File) {
  data class Note(val id: String, val title: String, val body: String, val updatedAt: Long)

  private val file = AtomicFile(File(directory, "decoy-notes.json"))

  fun read(): List<Note> = synchronized(lock) { readLocked() }

  fun save(note: Note): List<Note> = synchronized(lock) {
    validate(note)
    val notes = readLocked().filterNot { it.id == note.id } + note
    require(notes.size <= MAX_NOTES)
    writeLocked(notes)
    notes.sortedByDescending { it.updatedAt }
  }

  fun delete(id: String): List<Note> = synchronized(lock) {
    val notes = readLocked().filterNot { it.id == id }
    writeLocked(notes)
    notes
  }

  private fun readLocked(): List<Note> {
    val text = try {
      file.openRead().use {
        check(it.channel.size() <= 8 * 1024 * 1024) { "Notes file too large" }
        it.bufferedReader(Charsets.UTF_8).readText()
      }
    } catch (e: FileNotFoundException) {
      if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
      throw e
    }
    val json = JSONObject(text)
    check(json.getInt("version") == 1)
    val array = json.getJSONArray("notes")
    check(array.length() <= MAX_NOTES)
    val notes = (0 until array.length()).map {
      val item = array.getJSONObject(it)
      Note(item.getString("id"), item.getString("title"), item.getString("body"), item.getLong("updatedAt")).also(::validate)
    }
    check(notes.map { it.id }.distinct().size == notes.size)
    return notes.sortedByDescending { it.updatedAt }
  }

  private fun validate(note: Note) {
    require(note.id.isNotBlank() && note.id.length <= 64)
    require(note.title.length <= MAX_TITLE && note.body.length <= MAX_BODY && note.updatedAt >= 0)
  }

  private fun writeLocked(notes: List<Note>) {
    val array = JSONArray()
    notes.forEach {
      array.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("updatedAt", it.updatedAt))
    }
    val bytes = JSONObject().put("version", 1).put("notes", array).toString().toByteArray(Charsets.UTF_8)
    require(bytes.size <= 8 * 1024 * 1024)
    val output = file.startWrite()
    try {
      output.write(bytes)
      file.finishWrite(output)
    } catch (e: Exception) {
      file.failWrite(output)
      throw e
    }
  }

  companion object {
    const val MAX_NOTES = 100
    const val MAX_TITLE = 120
    const val MAX_BODY = 20_000
    // Survives activity recreation; ordered writes are not cancelled with the UI.
    val io = Executors.newSingleThreadExecutor()
    private val lock = Any()
  }
}
