package com.tibagni.logviewer

import com.tibagni.logviewer.filter.Filter
import com.tibagni.logviewer.log.LogEntry
import com.tibagni.logviewer.log.LogStream
import com.tibagni.logviewer.preferences.LogViewerPreferences
import org.apache.commons.io.FilenameUtils
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.function.Consumer

/**
 * Coordinates raw log file I/O loading, asynchronous stream parsing, and potential bugreport extraction.
 *
 * This class encapsulates background loading tasks away from the main presenter interface.
 *
 * Thread Safety: Parses files offloaded to background execution worker pools via [presenter.doAsync],
 * publishing periodic progress updates to the UI, and posting UI updates back to the Swing EDT
 * using [presenter.doOnUiThread].
 *
 * @property presenter The presenter backing the log viewer operations and thread context.
 * @property view The UI View abstraction used to push log results, available streams, and loader statuses.
 * @property userPrefs System preferences specifying remember filters and startup options.
 * @property logsRepository Primary repository executing text line extraction and stream matching.
 * @property myLogsRepository Repository tracking highlighted user logs list.
 * @property allowedStreamsMap Map of active stream filtering parameters.
 * @property filteredLogs Thread-shared mutable collection holding the parsed logs matching filters.
 * @property cachedAllowedFilteredLogs Thread-shared list filtered log entries matching allowed streams.
 * @property filterCoordinator Coordinator managing active filter compilation and application.
 */
class LogLoader(
  private val presenter: LogViewerPresenterImpl,
  private val view: LogViewerPresenterView,
  private val userPrefs: LogViewerPreferences,
  private val logsRepository: LogsRepository,
  private val myLogsRepository: MyLogsRepository,
  private val allowedStreamsMap: MutableMap<LogStream, Boolean>,
  private val filteredLogs: MutableList<LogEntry>,
  private val cachedAllowedFilteredLogs: MutableList<LogEntry>,
  private val filterCoordinator: FilterCoordinator
) {

  /**
   * Asynchronously parses and loads the target log files using UTF-8 charset.
   *
   * @param logFiles Array of File instances pointing to target log source files.
   */
  fun loadLogs(logFiles: Array<File>) {
    loadLogs(logFiles, StandardCharsets.UTF_8)
  }

  /**
   * Asynchronously parses and loads the target log files using the specified character encoding.
   *
   * Triggers the parser background pipeline and offloads parsing tasks off the main Swing thread.
   * On completion, matches loaded lines with open filter criteria and triggers potential bugreport notices.
   *
   * @param logFiles Array of target log files.
   * @param charset Character encoding specification.
   */
  fun loadLogs(logFiles: Array<File>, charset: Charset) {
    filterCoordinator.cleanUpFilterTempInfo()
    presenter.doAsync {
      try {
        logsRepository.openLogFiles(logFiles, charset, ProgressReporter { progress, note -> presenter.updateProgress(progress, note) })
        rebuildLogStreamsMap(logsRepository.availableStreams)
        filteredLogs.clear()
        cachedAllowedFilteredLogs.clear()
        cachedAllowedFilteredLogs.addAll(filterCoordinator.excludeNonAllowedStreams(filteredLogs))

        val skippedLogs = logsRepository.lastSkippedLogFiles
        val bugReports = logsRepository.potentialBugReports
        val myLogsChanged = presenter.updateMyLogs()

        presenter.doOnUiThread {
          view.showFilteredLogs(cachedAllowedFilteredLogs)
          view.showLogs(logsRepository.currentlyOpenedLogs)
          view.showAvailableLogStreams(allowedStreamsMap.keys)

          if (myLogsChanged) {
            view.showMyLogs(myLogsRepository.logs)
          }

          if (logsRepository.currentlyOpenedLogs.isNotEmpty()) {
            val logsPath = FilenameUtils.getFullPath(logFiles[0].absolutePath)
            view.showCurrentLogsLocation(logsPath)
            val appliedFiltersCount = filterCoordinator.getFiltersThat { it.isApplied }.size
            if (appliedFiltersCount > 0) {
              filterCoordinator.applyFilters()
            }

            if (skippedLogs.isNotEmpty()) {
              view.showSkippedLogsMessage(skippedLogs)
            }

            if (bugReports.isEmpty()) {
              view.closeCurrentlyOpenedBugReports()
            } else {
              val entry = bugReports.entries.iterator().next()
              view.showOpenPotentialBugReport(entry.key, entry.value)
            }
          } else {
            view.showCurrentLogsLocation(null)
            view.showErrorMessage("No logs found")
          }
        }
      } catch (e: OpenLogsException) {
        presenter.doOnUiThread { view.showErrorMessage(e.message) }
      }
    }
  }

  /**
   * Reloads currently opened log files with a different character set encoding.
   */
  fun refreshLogsWithDifferentCharset(charset: Charset) {
    if (logsRepository.currentlyOpenedLogFiles.isEmpty()) {
      view.showErrorMessage("No logs currently open")
    } else {
      loadLogs(logsRepository.currentlyOpenedLogFiles.toTypedArray(), charset)
    }
  }

  /**
   * Reloads and parses the currently opened log files.
   */
  fun refreshLogs() {
    if (logsRepository.currentlyOpenedLogFiles.isEmpty()) {
      view.showErrorMessage("No logs to be refreshed")
    } else {
      loadLogs(logsRepository.currentlyOpenedLogFiles.toTypedArray())
    }
  }

  /**
   * Rebuilds the internal mapping of available log streams, enabling all streams by default.
   */
  private fun rebuildLogStreamsMap(availableStreams: Set<LogStream>) {
    allowedStreamsMap.clear()
    for (s in availableStreams) {
      allowedStreamsMap[s] = true
    }
  }
}
