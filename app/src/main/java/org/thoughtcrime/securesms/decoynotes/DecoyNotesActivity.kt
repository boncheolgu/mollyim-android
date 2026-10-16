package org.thoughtcrime.securesms.decoynotes

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.ScreenLockController
import java.util.UUID

/** Final and non-exported. Only a local notepad; never authenticates or navigates to app content. */
class DecoyNotesActivity : AppCompatActivity() {
  private lateinit var store: DecoyNotesStore
  private lateinit var list: ListView
  private lateinit var editor: View
  private lateinit var titleInput: EditText
  private lateinit var bodyInput: EditText
  private lateinit var status: TextView
  private lateinit var newButton: View
  private lateinit var backButton: View
  private lateinit var deleteButton: View
  private val handler = Handler(Looper.getMainLooper())
  private val autosave = Runnable { saveCurrent() }
  private var notes = emptyList<DecoyNotesStore.Note>()
  private var currentId: String? = null
  private var editing = false
  private var loaded = false
  private var populating = false
  private var dirty = false
  private var navigating = false
  private var revision = 0L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.decoy_notes_activity)
    ScreenLockController.revealExemptWindow(window)
    store = DecoyNotesStore(applicationContext.noBackupFilesDir)
    list = findViewById(R.id.decoy_notes_list)
    editor = findViewById(R.id.decoy_notes_editor)
    titleInput = findViewById(R.id.decoy_notes_title_input)
    bodyInput = findViewById(R.id.decoy_notes_body_input)
    status = findViewById(R.id.decoy_notes_status)
    newButton = findViewById(R.id.decoy_notes_new)
    backButton = findViewById(R.id.decoy_notes_back)
    deleteButton = findViewById(R.id.decoy_notes_delete)
    list.emptyView = findViewById(R.id.decoy_notes_empty)
    titleInput.filters = arrayOf(InputFilter.LengthFilter(DecoyNotesStore.MAX_TITLE))
    bodyInput.filters = arrayOf(InputFilter.LengthFilter(DecoyNotesStore.MAX_BODY))
    titleInput.doAfterTextChanged { changed() }
    bodyInput.doAfterTextChanged { changed() }
    newButton.setOnClickListener {
      if (loaded && notes.size < DecoyNotesStore.MAX_NOTES) showEditor(null)
      else if (loaded) Toast.makeText(this, R.string.decoy_notes_limit, Toast.LENGTH_LONG).show()
    }
    backButton.setOnClickListener { saveCurrent(leave = true) }
    deleteButton.setOnClickListener { confirmDelete() }
    list.setOnItemClickListener { _, _, position, _ -> if (loaded) showEditor(notes[position]) }
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (navigating) return
        if (editing) saveCurrent(leave = true) else finish()
      }
    })
    val root = findViewById<View>(R.id.decoy_notes_root)
    val padding = root.paddingLeft
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
      view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
      insets
    }
    ViewCompat.requestApplyInsets(root)
    newButton.isEnabled = false
    status.setText(R.string.decoy_notes_loading)
    val restoreId = savedInstanceState?.getString("note")
    DecoyNotesStore.io.execute {
      val result = runCatching { store.read() }
      handler.post {
        if (isFinishing || isDestroyed) return@post
        result.onSuccess {
          notes = it
          loaded = true
          newButton.isEnabled = true
          if (restoreId != null) showEditor(notes.firstOrNull { note -> note.id == restoreId }, restoreId) else showList()
        }.onFailure {
          // Do not treat a damaged file as an empty notepad and overwrite it.
          status.setText(R.string.decoy_notes_load_failed)
        }
      }
    }
  }

  private fun changed() {
    if (populating || !loaded || !editing || navigating) return
    revision++
    dirty = true
    status.setText(R.string.decoy_notes_saving)
    handler.removeCallbacks(autosave)
    handler.postDelayed(autosave, 500)
  }

  private fun showEditor(note: DecoyNotesStore.Note?, restoreId: String? = null) {
    populating = true
    currentId = note?.id ?: restoreId ?: UUID.randomUUID().toString()
    editing = true
    dirty = false
    revision++
    titleInput.setText(note?.title.orEmpty())
    bodyInput.setText(note?.body.orEmpty())
    populating = false
    list.visibility = View.GONE
    list.emptyView?.visibility = View.GONE
    editor.visibility = View.VISIBLE
    newButton.visibility = View.GONE
    backButton.visibility = View.VISIBLE
    deleteButton.visibility = View.VISIBLE
    status.setText(R.string.decoy_notes_autosave)
    if (note == null) {
      titleInput.requestFocus()
      titleInput.post {
        if (editing && !isFinishing && !isDestroyed) {
          (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(titleInput, InputMethodManager.SHOW_IMPLICIT)
        }
      }
    }
  }

  private fun showList() {
    editing = false
    currentId = null
    editor.visibility = View.GONE
    newButton.visibility = View.VISIBLE
    backButton.visibility = View.GONE
    deleteButton.visibility = View.GONE
    status.text = ""
    renderList()
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
  }

  private fun renderList() {
    list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, notes.map {
      val title = it.title.ifBlank { getString(R.string.decoy_notes_untitled) }
      val preview = it.body.lineSequence().firstOrNull { line -> line.isNotBlank() }?.take(80).orEmpty()
      if (preview.isEmpty()) title else "$title\n$preview"
    })
    list.visibility = View.VISIBLE
  }

  private fun setNavigating(value: Boolean) {
    navigating = value
    titleInput.isEnabled = !value
    bodyInput.isEnabled = !value
    backButton.isEnabled = !value
    deleteButton.isEnabled = !value
  }

  private fun saveCurrent(leave: Boolean = false) {
    handler.removeCallbacks(autosave)
    val id = currentId ?: return
    if (!loaded || !editing || navigating || (!dirty && !leave)) return
    val note = DecoyNotesStore.Note(id, titleInput.text.toString(), bodyInput.text.toString(), System.currentTimeMillis())
    // Empty new drafts do not create phantom entries, but clearing an existing note is saved.
    val emptyDraft = note.title.isBlank() && note.body.isBlank() && notes.none { it.id == id }
    val savedRevision = revision
    dirty = false
    if (leave) setNavigating(true)
    DecoyNotesStore.io.execute {
      val result = runCatching { if (emptyDraft) store.delete(id) else store.save(note) }
      handler.post {
        if (isFinishing || isDestroyed) return@post
        if (leave) setNavigating(false)
        result.onSuccess {
          notes = it
          if (!editing) renderList()
          else if (currentId == id && revision == savedRevision) {
            status.setText(R.string.decoy_notes_saved)
            if (leave) showList()
          }
        }.onFailure {
          if (editing && currentId == id) {
            dirty = true
            status.setText(R.string.decoy_notes_save_failed)
          }
        }
      }
    }
  }

  private fun confirmDelete() {
    if (!editing || navigating) return
    val id = currentId ?: return
    val dialog = AlertDialog.Builder(this)
      .setMessage(R.string.decoy_notes_delete_confirm)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.decoy_notes_delete) { _, _ ->
        handler.removeCallbacks(autosave)
        dirty = false
        setNavigating(true)
        DecoyNotesStore.io.execute {
          val result = runCatching { store.delete(id) }
          handler.post {
            if (isFinishing || isDestroyed) return@post
            setNavigating(false)
            result.onSuccess { notes = it; showList() }
              .onFailure { dirty = true; status.setText(R.string.decoy_notes_save_failed) }
          }
        }
      }.create()
    dialog.setOnShowListener { ScreenLockController.revealExemptWindow(dialog.window) }
    dialog.show()
  }

  override fun onPause() {
    saveCurrent()
    super.onPause()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    saveCurrent()
    if (editing) outState.putString("note", currentId)
    super.onSaveInstanceState(outState)
  }

  override fun onDestroy() {
    handler.removeCallbacks(autosave)
    super.onDestroy()
  }
}
