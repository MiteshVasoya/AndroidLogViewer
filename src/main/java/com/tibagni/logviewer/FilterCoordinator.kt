package com.tibagni.logviewer

import com.tibagni.logviewer.filter.Filter
import com.tibagni.logviewer.filter.Filters
import com.tibagni.logviewer.log.LogEntry
import com.tibagni.logviewer.log.LogStream
import com.tibagni.logviewer.preferences.LogViewerPreferences
import com.tibagni.logviewer.util.StringUtils
import org.apache.commons.lang3.ArrayUtils
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Coordinates filter state management, group mutations, and background log filtering.
 *
 * This class isolates the complex filter logic away from the core presenter. It operates
 * on shared mutable data structures that remain synchronized in real-time with the UI presentation.
 *
 * Thread Safety: Filter calculations are offloaded to background threads using [presenter.doAsync],
 * while all UI transitions and callbacks are routed back to the Swing Event Dispatch Thread (EDT)
 * via [presenter.doOnUiThread].
 *
 * @property presenter The presenter backing the log viewer operations and thread context.
 * @property view The UI View abstraction used to push filter configurations and alerts.
 * @property userPrefs System preferences that dictate reapplication and persistence behavior.
 * @property filtersRepository Core storage layer maintaining filters, files, and group hierarchies.
 * @property logsRepository Read-only access to currently opened log structures.
 * @property allowedStreamsMap Map of active stream filtering parameters.
 * @property filteredLogs Thread-shared mutable collection holding the parsed logs matching filters.
 * @property cachedAllowedFilteredLogs Thread-shared list filtered log entries matching allowed streams.
 * @property unsavedFilterGroups Mutable registry of group files that have modified states.
 */
class FilterCoordinator(
  private val presenter: LogViewerPresenterImpl,
  private val view: LogViewerPresenterView,
  private val userPrefs: LogViewerPreferences,
  private val filtersRepository: FiltersRepository,
  private val logsRepository: LogsRepository,
  private val allowedStreamsMap: MutableMap<LogStream, Boolean>,
  private val filteredLogs: MutableList<LogEntry>,
  private val cachedAllowedFilteredLogs: MutableList<LogEntry>,
  private val unsavedFilterGroups: MutableList<String>
) {

  /**
   * Appends a new filter to the specified group.
   *
   * @param group Group name to append the filter to.
   * @param newFilter The concrete Filter definition (regex, colors, state).
   */
  fun addFilter(group: String?, newFilter: Filter) {
    addFilter(group, newFilter, false)
  }

  /**
   * Appends a new filter to the specified group, optionally bypassing immediate reapplication.
   *
   * @param group Group name to append the filter to.
   * @param newFilter The concrete Filter definition.
   * @param ignoreReapply True to skip immediate background filter recalculation.
   */
  fun addFilter(group: String?, newFilter: Filter, ignoreReapply: Boolean) {
    if (group != null && !StringUtils.isEmpty(group) && newFilter != null) {
      filtersRepository.addFilter(group, newFilter)
      view.configureFiltersList(filtersRepository.currentlyOpenedFilters)
      checkForUnsavedChanges()

      if (!ignoreReapply && userPrefs.reapplyFiltersAfterEdit) {
        newFilter.isApplied = true
        applyFilters()
      }
    }
  }

  /**
   * Adds an empty filter group to the repository.
   *
   * @param group Name of the new filter group.
   * @return The canonical added group name, or null if validation failed.
   */
  fun addGroup(group: String?): String? {
    if (group != null && !StringUtils.isEmpty(group)) {
      val addedGroup = filtersRepository.addGroup(group)
      view.configureFiltersList(filtersRepository.currentlyOpenedFilters)
      return addedGroup
    }
    return null
  }

  /**
   * Returns a copy list of all currently opened filter group names.
   */
  fun getGroups(): List<String> {
    return ArrayList(filtersRepository.currentlyOpenedFilters.keys)
  }

  /**
   * Deletes multiple filters from a specific group by their table indices.
   * Triggers reapplication if any deleted filter was active.
   *
   * @param group Group name containing the targets.
   * @param indices Array of absolute filter indices in the group.
   */
  fun removeFilters(group: String?, indices: IntArray) {
    if (group != null) {
      val deletedFilters = filtersRepository.deleteFilters(group, indices)
      val shouldReapply = deletedFilters.stream().anyMatch { it.isApplied }
      view.configureFiltersList(filtersRepository.currentlyOpenedFilters)

      if (filtersRepository.currentlyOpenedFilters.isNotEmpty()) {
        checkForUnsavedChanges()
      }

      if (shouldReapply) {
        applyFilters()
      }
    }
  }

  /**
   * Moves a block of filters from a source group to a destination group.
   *
   * @param origGroup Source group name.
   * @param destGroup Target group name.
   * @param indices Array of filter indices to move from the source group.
   */
  fun moveFilters(origGroup: String?, destGroup: String?, indices: IntArray) {
    if (origGroup != null && destGroup != null) {
      if (StringUtils.areEquals(origGroup, destGroup)) {
        return
      }

      val movingFilters = filtersRepository.deleteFilters(origGroup, indices)
      filtersRepository.addFilters(destGroup, movingFilters)

      val shouldReapply = movingFilters.stream().anyMatch { it.isApplied }
      view.configureFiltersList(filtersRepository.currentlyOpenedFilters)
      checkForUnsavedChanges()

      if (shouldReapply) {
        applyFilters()
      }
    }
  }

  /**
   * Completely removes a filter group, prompting to save if there are unsaved modifications.
   *
   * @param group Name of the group to discard.
   */
  fun removeGroup(group: String?) {
    if (group != null && !StringUtils.isEmpty(group)) {
      val unsavedChange = unsavedFilterGroups.contains(group)

      if (unsavedChange) {
        val userSelection = view.showAskToSaveFilterDialog(group)
        if (userSelection == LogViewerPresenter.UserSelection.CONFIRMED) {
          presenter.saveFilters(group)
        } else if (userSelection == LogViewerPresenter.UserSelection.CANCELLED) {
          return
        }
      }

      val filtersFromGroup = filtersRepository.currentlyOpenedFilters[group]
      val groupFile = filtersRepository.currentlyOpenedFilterFiles[group]
      if (filtersFromGroup != null) {
        filtersRepository.deleteGroup(group)
        view.configureFiltersList(filtersRepository.currentlyOpenedFilters)
        checkForUnsavedChanges()

        val shouldReapply = filtersFromGroup.stream().anyMatch { it.isApplied }
        if (shouldReapply) {
          applyFilters()
        }
      }

      if (groupFile != null) {
        val currentSavedPaths = userPrefs.lastFilterPaths
        if (currentSavedPaths.isNotEmpty()) {
          val i = ArrayUtils.indexOf(currentSavedPaths, groupFile)
          if (i >= 0) {
            val newSavedPaths = ArrayUtils.remove(currentSavedPaths, i)
            userPrefs.lastFilterPaths = newSavedPaths
          }
        }
      }
    }
  }

  /**
   * Asynchronously calculates and applies all active filters to the currently loaded logs.
   *
   * Offloads the heavy stream filtering iteration to a background thread to prevent GUI lockup.
   * Progress updates are published incrementally via the [ProgressReporter] callback.
   *
   * Pre-conditions: Logs must be opened in [logsRepository]. If no logs are loaded,
   * updates the view with empty results immediately.
   */
  fun applyFilters() {
    presenter.testStats.applyFiltersCallCount++
    if (logsRepository.currentlyOpenedLogs.isEmpty()) {
      presenter.doOnUiThread { view.showFilteredLogs(cachedAllowedFilteredLogs) }
      return
    }

    cleanUpFilterInfoFromLogEntries()
    cleanUpFilterTempInfo()

    val toApply = getFiltersThat { it.isApplied }
    presenter.doAsync {
      filteredLogs.clear()
      filteredLogs.addAll(
        Filters.applyMultipleFilters(
          logsRepository.currentlyOpenedLogs,
          toApply.toTypedArray(),
          ProgressReporter { progress, note -> presenter.updateProgress(progress, note) }
        )
      )
      cachedAllowedFilteredLogs.clear()
      cachedAllowedFilteredLogs.addAll(excludeNonAllowedStreams(filteredLogs))
      updateFiltersContextInfo()
      presenter.doOnUiThread { view.showFilteredLogs(cachedAllowedFilteredLogs) }
    }
  }

  /**
   * Marks unsaved changes and reapplies filters when a specific filter's attributes are edited.
   */
  fun filterEdited(filter: Filter) {
    checkForUnsavedChanges()

    if (userPrefs.reapplyFiltersAfterEdit) {
      filter.isApplied = true
      applyFilters()
    }
  }

  /**
   * Toggles the active 'applied' status of all filters belonging to a specific group.
   */
  fun setAllFiltersApplied(group: String?, isApplied: Boolean) {
    if (group != null) {
      forEachFilterInGroup(group, Consumer { it.isApplied = isApplied })
      applyFilters()
    }
  }

  /**
   * Toggles the active 'applied' status of every open filter in the workspace.
   */
  fun setAllFiltersApplied(isApplied: Boolean) {
    forEachFilter(Consumer { it.isApplied = isApplied })
    applyFilters()
  }

  /**
   * Resets active filters cache inside log entry instances before re-filtering.
   */
  fun cleanUpFilterInfoFromLogEntries() {
    if (filteredLogs != null) {
      for (entry in filteredLogs) {
        entry.appliedFilter = null
      }
    }
  }

  /**
   * Clears temporary metadata caches inside open filter definitions.
   */
  fun cleanUpFilterTempInfo() {
    forEachFilter(Consumer { it.resetTemporaryInfo() })
  }

  /**
   * Iterates through all open groups to identify modified states that require saving.
   */
  fun checkForUnsavedChanges() {
    unsavedFilterGroups.clear()
    val changedGroups = filtersRepository.getChangedGroupsSinceLastOpened()
    val allGroups = filtersRepository.currentlyOpenedFilters.keys
    for (group in allGroups) {
      val changed = changedGroups.contains(group)
      if (changed) {
        unsavedFilterGroups.add(group)
      }
    }
  }

  /**
   * Filters the incoming list of entries to exclude log lines from unchecked streams.
   */
  fun excludeNonAllowedStreams(entries: List<LogEntry>): List<LogEntry> {
    if (allowedStreamsMap.isEmpty()) {
      return entries
    }

    val result = ArrayList<LogEntry>()
    val allowedStreams = HashSet<LogStream>()
    for ((key, value) in allowedStreamsMap) {
      if (value) {
        allowedStreams.add(key)
      }
    }

    for (entry in entries) {
      if (allowedStreams.contains(entry.stream)) {
        result.add(entry)
      }
    }

    return result
  }

  /**
   * Returns a copy list of filters matching the given predicate.
   */
  fun getFiltersThat(predicate: Predicate<Filter>): List<Filter> {
    val filters = ArrayList<Filter>()
    forEachFilter(Consumer {
      if (predicate.test(it)) {
        filters.add(it)
      }
    })
    return filters
  }

  /**
   * Iterates through every open filter.
   */
  fun forEachFilter(consumer: Consumer<Filter>) {
    for (filtersList in filtersRepository.currentlyOpenedFilters.values) {
      filtersList.forEach(consumer)
    }
  }

  /**
   * Iterates through every filter in a specific group.
   */
  fun forEachFilterInGroup(group: String?, consumer: Consumer<Filter>) {
    if (group != null) {
      val filtersList = filtersRepository.currentlyOpenedFilters[group]
      filtersList?.forEach(consumer)
    }
  }

  /**
   * Updates allowed streams settings inside the filters' temporary metadata context.
   */
  fun updateFiltersContextInfo() {
    val allowedStreams = HashSet<LogStream>()
    for ((key, value) in allowedStreamsMap) {
      if (value) {
        allowedStreams.add(key)
      }
    }

    forEachFilter(Consumer { filter ->
      val filterTemporaryInfo = filter.temporaryInfo
      if (filterTemporaryInfo != null) {
        filterTemporaryInfo.setAllowedStreams(allowedStreams)
      }
    })
  }
}
