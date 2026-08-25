package com.tibagni.logviewer.fakes

import com.tibagni.logviewer.FiltersRepository
import com.tibagni.logviewer.LogsRepository
import com.tibagni.logviewer.MyLogsRepository
import com.tibagni.logviewer.OpenFiltersException
import com.tibagni.logviewer.OpenLogsException
import com.tibagni.logviewer.PersistFiltersException
import com.tibagni.logviewer.ProgressReporter
import com.tibagni.logviewer.filter.Filter
import com.tibagni.logviewer.log.LogEntry
import com.tibagni.logviewer.log.LogStream
import com.tibagni.logviewer.preferences.LogViewerPreferences
import java.io.File
import java.nio.charset.Charset
import java.util.*
import kotlin.math.abs

/**
 * State-Based Fake implementation of [LogViewerPreferences] for testing.
 *
 * Avoids Mockito overhead by keeping preferences state entirely in-memory.
 */
class FakeLogViewerPreferences : LogViewerPreferences {
  override var defaultFiltersPath: File = File(".")
  override var lastFilterPaths: Array<File> = emptyArray()
  override var defaultLogsPath: File = File(".")
  override var lookAndFeel: String = ""
  override var openLastFilter: Boolean = false
  override var reapplyFiltersAfterEdit: Boolean = false
  override var rememberAppliedFilters: Boolean = false
  override var preferredTextEditor: File? = null
  override var collapseAllGroupsStartup: Boolean = false
  override var showLineNumbers: Boolean = false
  override var applyFilterOnCheck: Boolean = false

  private val filterIndices = mutableMapOf<String, List<Int>>()
  private val listeners = mutableListOf<LogViewerPreferences.Listener>()

  override fun setAppliedFiltersIndices(group: String, indices: List<Int>) {
    filterIndices[group] = indices
  }

  override fun getAppliedFiltersIndices(group: String): List<Int> {
    return filterIndices[group] ?: emptyList()
  }

  override fun addPreferenceListener(listener: LogViewerPreferences.Listener) {
    listeners.add(listener)
  }

  override fun removePreferenceListener(listener: LogViewerPreferences.Listener) {
    listeners.remove(listener)
  }
}

/**
 * State-Based Fake implementation of [LogsRepository] for testing.
 *
 * Supports in-memory log parsing simulation and visible window sub-listing.
 */
class FakeLogsRepository : LogsRepository {
  override var currentlyOpenedLogFiles: List<File> = emptyList()

  private val _currentlyOpenedLogs = mutableListOf<LogEntry>()
  override val currentlyOpenedLogs: List<LogEntry>
    get() {
      if (_currentlyOpenedLogs.isEmpty()) return emptyList()
      val start = if (firstVisibleLogIndex in _currentlyOpenedLogs.indices) firstVisibleLogIndex else 0
      val end = if (lastVisibleLogIndex in _currentlyOpenedLogs.indices) lastVisibleLogIndex else _currentlyOpenedLogs.lastIndex
      if (start > end) return emptyList()
      return _currentlyOpenedLogs.subList(start, end + 1)
    }

  override var availableStreams: Set<LogStream> = emptySet()
  override var lastSkippedLogFiles: List<String> = emptyList()
  override var potentialBugReports: Map<String, String> = emptyMap()

  override var firstVisibleLogIndex: Int = 0
  override var lastVisibleLogIndex: Int = -1
    get() = if (field == -1) _currentlyOpenedLogs.lastIndex else field

  override val allLogsSize: Int
    get() = _currentlyOpenedLogs.size

  /**
   * Sets the core mock log entries list and resets bounds.
   */
  fun setCurrentlyOpenedLogs(logs: List<LogEntry>) {
    _currentlyOpenedLogs.clear()
    _currentlyOpenedLogs.addAll(logs)
    firstVisibleLogIndex = 0
    lastVisibleLogIndex = -1
  }

  /**
   * Simulation of loading logs from filesystem.
   *
   * @throws OpenLogsException If [shouldThrowOnOpen] is set.
   */
  var shouldThrowOnOpen: Throwable? = null
  override fun openLogFiles(files: Array<File>, charset: Charset, progressReporter: ProgressReporter) {
    shouldThrowOnOpen?.let { throw OpenLogsException(it.message, it) }
    currentlyOpenedLogFiles = files.toList()
    progressReporter.onProgress(100, "Completed in fake")
  }

  /**
   * Performs in-memory binary search to locate duplicate timestamp log entries.
   */
  override fun getMatchingLogEntry(entry: LogEntry): LogEntry? {
    val cmp = Comparator.comparing { o: LogEntry -> o.timestamp }
    val indexFound = Collections.binarySearch(currentlyOpenedLogs, entry, cmp)
    if (indexFound >= 0) {
      var i = indexFound
      while (i >= 0 && currentlyOpenedLogs[i].timestamp == entry.timestamp) {
        i--
      }
      i++
      while (i < currentlyOpenedLogs.size && currentlyOpenedLogs[i].timestamp <= entry.timestamp) {
        if (currentlyOpenedLogs[i].logText == entry.logText) {
          return currentlyOpenedLogs[i]
        }
        i++
      }
    }
    return null
  }
}

/**
 * State-Based Fake implementation of [FiltersRepository] for testing.
 *
 * Simulates groups creation, filters addition, deletion, and sorting order.
 */
class FakeFiltersRepository : FiltersRepository {
  private val _currentlyOpenedFilters = mutableMapOf<String, MutableList<Filter>>()
  override val currentlyOpenedFilters: Map<String, List<Filter>>
    get() = _currentlyOpenedFilters

  private val _currentlyOpenedFilterFiles = mutableMapOf<String, File>()
  override val currentlyOpenedFilterFiles: Map<String, File>
    get() = _currentlyOpenedFilterFiles

  var shouldThrowOnOpen: Throwable? = null
  var shouldThrowOnPersist: Throwable? = null

  val changedGroups = mutableListOf<String>()

  /**
   * Registers dummy files in the active loaded groups dictionary.
   */
  override fun openFilterFiles(files: Array<File>) {
    shouldThrowOnOpen?.let { throw OpenFiltersException(it.message, it) }
    for (file in files) {
      val group = file.name
      _currentlyOpenedFilterFiles[group] = file
      if (!_currentlyOpenedFilters.containsKey(group)) {
        _currentlyOpenedFilters[group] = mutableListOf()
      }
    }
  }

  /**
   * Injects mock filters dictionary directly into internal state.
   */
  fun setCurrentlyOpenedFilters(filters: Map<String, List<Filter>>) {
    _currentlyOpenedFilters.clear()
    for ((group, list) in filters) {
      _currentlyOpenedFilters[group] = list.toMutableList()
    }
  }

  override fun addFilter(group: String, filter: Filter) {
    _currentlyOpenedFilters.getOrPut(group) { mutableListOf() }.add(filter)
  }

  override fun addFilters(group: String, filters: List<Filter>) {
    _currentlyOpenedFilters.getOrPut(group) { mutableListOf() }.addAll(filters)
  }

  override fun deleteFilters(group: String, indices: IntArray): List<Filter> {
    val filters = _currentlyOpenedFilters[group] ?: return emptyList()
    val removed = mutableListOf<Filter>()
    val sortedIndices = indices.sortedDescending()
    for (idx in sortedIndices) {
      if (idx in filters.indices) {
        removed.add(filters.removeAt(idx))
      }
    }
    return removed.reversed()
  }

  override fun reorderFilters(group: String, indOrig: Int, indDest: Int) {
    val filters = _currentlyOpenedFilters[group] ?: return
    if (indOrig in filters.indices && indDest in filters.indices) {
      val item = filters.removeAt(indOrig)
      filters.add(indDest, item)
    }
  }

  override fun addGroup(group: String): String {
    if (!_currentlyOpenedFilters.containsKey(group)) {
      _currentlyOpenedFilters[group] = mutableListOf()
    }
    return group
  }

  override fun deleteGroup(group: String): Boolean {
    val removedFilters = _currentlyOpenedFilters.remove(group)
    val removedFile = _currentlyOpenedFilterFiles.remove(group)
    return removedFilters != null || removedFile != null
  }

  override fun persistGroup(file: File, group: String) {
    shouldThrowOnPersist?.let { throw PersistFiltersException(it.message, it) }
    _currentlyOpenedFilterFiles[group] = file
  }

  override fun getChangedGroupsSinceLastOpened(): List<String> {
    return changedGroups
  }

  override fun closeAllFilters() {
    _currentlyOpenedFilters.clear()
    _currentlyOpenedFilterFiles.clear()
    changedGroups.clear()
  }
}

/**
 * State-Based Fake implementation of [MyLogsRepository] for testing.
 *
 * Keeps custom highlighted logs and ensures sorting constraint.
 */
class FakeMyLogsRepository : MyLogsRepository {
  private val _logs = mutableListOf<LogEntry>()
  override val logs: List<LogEntry>
    get() = _logs

  override fun addLogEntries(entries: List<LogEntry>) {
    for (entry in entries) {
      insertInOrder(entry)
    }
  }

  override fun removeLogEntries(entries: List<LogEntry>) {
    _logs.removeAll(entries)
  }

  override fun reset(entries: List<LogEntry>) {
    _logs.clear()
    _logs.addAll(entries)
  }

  private fun insertInOrder(entry: LogEntry) {
    val indexFound = Collections.binarySearch(_logs, entry)
    if (indexFound < 0) {
      val targetIndex = abs(indexFound + 1)
      _logs.add(targetIndex, entry)
    }
  }
}
