package com.tibagni.logviewer;

import com.tibagni.logviewer.filter.Filter;
import com.tibagni.logviewer.filter.Filters;
import com.tibagni.logviewer.log.LogEntry;
import com.tibagni.logviewer.log.LogStream;
import com.tibagni.logviewer.log.LogTimestamp;
import com.tibagni.logviewer.logger.Logger;
import com.tibagni.logviewer.preferences.LogViewerPreferences;
import com.tibagni.logviewer.util.StringUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.tibagni.logviewer.logger.ProfilerKt.wrapProfiler;

public class LogViewerPresenterImpl extends AsyncPresenter implements LogViewerPresenter {
  private final LogViewerPresenterView view;

  private final List<LogEntry> filteredLogs;
  private final List<LogEntry> cachedAllowedFilteredLogs;

  private final List<String> unsavedFilterGroups;
  private final Map<LogStream, Boolean> allowedStreamsMap;
  private final LogViewerPreferences userPrefs;

  private final LogsRepository logsRepository;
  private final MyLogsRepository myLogsRepository;
  private final FiltersRepository filtersRepository;

  private final FilterCoordinator filterCoordinator;
  private final LogLoader logLoader;

  LogViewerPresenterImpl(LogViewerPresenterView view,
                         LogViewerPreferences userPrefs,
                         LogsRepository logsRepository,
                         MyLogsRepository myLogsRepository,
                         FiltersRepository filtersRepository) {
    super(view);
    this.view = view;
    this.userPrefs = userPrefs;
    this.logsRepository = logsRepository;
    this.myLogsRepository = myLogsRepository;
    this.filtersRepository = filtersRepository;

    unsavedFilterGroups = new ArrayList<>();
    cachedAllowedFilteredLogs = new ArrayList<>();
    filteredLogs = new ArrayList<>();
    allowedStreamsMap = new HashMap<>();

    filterCoordinator = new FilterCoordinator(
        this,
        view,
        userPrefs,
        filtersRepository,
        logsRepository,
        allowedStreamsMap,
        filteredLogs,
        cachedAllowedFilteredLogs,
        unsavedFilterGroups
    );

    logLoader = new LogLoader(
        this,
        view,
        userPrefs,
        logsRepository,
        myLogsRepository,
        allowedStreamsMap,
        filteredLogs,
        cachedAllowedFilteredLogs,
        filterCoordinator
    );
  }

  @Override
  public void init() {
    // Check if we need to open the last opened filter
    if (userPrefs.getOpenLastFilter()) {
      File[] lastFilters = userPrefs.getLastFilterPaths();
      if (lastFilters.length > 0) {
        loadFilters(lastFilters, false);
      }
    }

    // Check if we need to collapse all groups on startup
    if (userPrefs.getCollapseAllGroupsStartup()) {
      view.collapseAllGroups();
    }
  }

  @Override
  public void addFilter(String group, Filter newFilter) {
    filterCoordinator.addFilter(group, newFilter);
  }

  @Override
  public void addFilter(String group, Filter newFilter, boolean ignoreReapply) {
    filterCoordinator.addFilter(group, newFilter, ignoreReapply);
  }

  @Override
  public String addGroup(String group) {
    return filterCoordinator.addGroup(group);
  }

  @Override
  public List<String> getGroups() {
    return filterCoordinator.getGroups();
  }

  @Override
  public void removeFilters(String group, int[] indices) {
    filterCoordinator.removeFilters(group, indices);
  }

  @Override
  public void moveFilters(String origGroup, String destGroup, int[] indices) {
    filterCoordinator.moveFilters(origGroup, destGroup, indices);
  }

  @Override
  public void removeGroup(String group) {
    filterCoordinator.removeGroup(group);
  }

  @Override
  public void reorderFilters(String group, int orig, int dest) {
    if (orig == dest) return;

    filtersRepository.reorderFilters(group, orig, dest);
    view.configureFiltersList(filtersRepository.getCurrentlyOpenedFilters());
    checkForUnsavedChanges();
  }

  @Override
  public int getNextFilteredLogForFilter(Filter filter, int firstLogIndexSearch) {
    // we need to navigate on the logs that are being shown on the UI,
    // so use 'cachedAllowedFilteredLogs' here
    if (cachedAllowedFilteredLogs.isEmpty()) {
      return -1;
    }

    if (firstLogIndexSearch < 0) {
      firstLogIndexSearch = -1;
    }

    if (firstLogIndexSearch >= cachedAllowedFilteredLogs.size()) {
      firstLogIndexSearch = cachedAllowedFilteredLogs.size() - 1;
    }

    int startSearch = firstLogIndexSearch + 1;
    int endSearch = startSearch + cachedAllowedFilteredLogs.size();

    for (int i = startSearch; i <= endSearch; i++) {
      int index = i % cachedAllowedFilteredLogs.size();
      if (filter.appliesTo(cachedAllowedFilteredLogs.get(index))) {
        if (index < firstLogIndexSearch) {
          view.showNavigationNextOver();
        }
        return index;
      }
    }

    return -1;
  }

  @Override
  public int getPrevFilteredLogForFilter(Filter filter, int firstLogIndexSearch) {
    // we need to navigate on the logs that are being shown on the UI,
    // so use 'cachedAllowedFilteredLogs' here
    if (cachedAllowedFilteredLogs.isEmpty()) {
      return -1;
    }

    if (firstLogIndexSearch < 0) {
      firstLogIndexSearch = -1;
    }

    if (firstLogIndexSearch >= cachedAllowedFilteredLogs.size()) {
      firstLogIndexSearch = cachedAllowedFilteredLogs.size() - 1;
    }

    int startSearch = firstLogIndexSearch < 0 ? firstLogIndexSearch : firstLogIndexSearch - 1;
    int endSearch = startSearch - cachedAllowedFilteredLogs.size();

    for (int i = startSearch; i >= endSearch; i--) {
      int index = i >= 0 ? i : (cachedAllowedFilteredLogs.size() + i);
      if (filter.appliesTo(cachedAllowedFilteredLogs.get(index))) {
        if (index > firstLogIndexSearch && firstLogIndexSearch >= 0) {
          view.showNavigationPrevOver();
        }
        return index;
      }
    }

    return -1;
  }

  @Override
  public void goToTimestamp(String timestamp) {
    try {
      String[] timestampParts = timestamp.split(" ");
      String[] date = timestampParts[0].split("-");
      String[] time = timestampParts.length > 1 ?
          // Allow the user to use ':' or '.' for time portion. In case it is a copy of what is in the logcat
          timestampParts[1].replaceAll("\\.", ":").split(":") :
          new String[]{};

      if (date.length != 2) throw new IllegalArgumentException("Invalid date!");

      int month = Integer.parseInt(date[0]);
      int day = Integer.parseInt(date[1]);

      // It does not matter if the timestamp is not complete as it should work with
      // approximate values. So, don't enforce it.
      int hour = (time.length > 0) ? Integer.parseInt(time[0]) : 0;
      int min = (time.length > 1) ? Integer.parseInt(time[1]) : 0;
      int sec = (time.length > 2) ? Integer.parseInt(time[2]) : 0;
      int hund = (time.length > 3) ? Integer.parseInt(time[3]) : 0;

      LogTimestamp searchTimestamp = new LogTimestamp(month, day, hour, min, sec, hund);
      Logger.info("Going to timestamp: " + searchTimestamp);

      int unfilteredLogIndex = wrapProfiler(
          "findClosestLogIndexByTimestamp-AllLogs",
          () -> findClosestLogIndexByTimestamp(searchTimestamp, logsRepository.getCurrentlyOpenedLogs())
      );
      int filteredLogIndex = wrapProfiler(
          "findClosestLogIndexByTimestamp-FilteredLogs",
          () -> findClosestLogIndexByTimestamp(searchTimestamp, cachedAllowedFilteredLogs)
      );

      view.showLogLocationAtSearchedTimestamp(unfilteredLogIndex, filteredLogIndex);
    } catch (Exception e) {
      Logger.error("Failed to parse timestamp: " + timestamp, e);
      view.showInvalidTimestampSearchError(timestamp);
    }
  }

  private int findClosestLogIndexByTimestamp(@NotNull LogTimestamp timestamp, List<LogEntry> logList) {
    if (logList == null || logList.isEmpty()) {
      return -1;
    }

    int index = -1;
    for (LogEntry entry : logList) {
      index++;
      if (timestamp.compareTo(entry.timestamp) == 0) {
        break;
      } else if (timestamp.compareTo(entry.timestamp) < 0) {
        // We want the log line before
        if (index > 0) index--;
        break;
      }
    }

    return index;
  }

  @Override
  public void saveFilters(String group) {
    File saveFile = filtersRepository.getCurrentlyOpenedFilterFiles().get(group);
    if (saveFile == null) {
      saveFile = view.showSaveFilters(group);
    }

    if (saveFile != null) {
      saveFilters(saveFile, group);
    }
  }

  private void saveFilters(File filterFile, String group) {
    try {
      filtersRepository.persistGroup(filterFile, group);
      // Call checkForUnsavedChanges to clear the 'unsaved changes' state
      checkForUnsavedChanges();
    } catch (PersistFiltersException e) {
      view.showErrorMessage(e.getMessage());
    }
  }

  @Override
  public void loadFilters(File[] filtersFiles, boolean keepCurrentFilters) {
    if (userPrefs.getRememberAppliedFilters()) {
      // First remember which filters are applied for the current files
      // So the next time these files are opened, we can re-apply the same filters
      rememberAppliedFilters();
    }

    if (!keepCurrentFilters) {
      // Do not keep current filters, clear everything before loading the new ones
      if (!filtersRepository.getCurrentlyOpenedFilters().isEmpty()) {
        boolean shouldAbort = !requestSaveUnsavedGroups();
        if (shouldAbort) {
          return;
        }
      }
      filtersRepository.closeAllFilters();
    }

    try {
      filtersRepository.openFilterFiles(filtersFiles);
    } catch (OpenFiltersException e) {
      view.showErrorMessage(e.getMessage());
    }

    Map<String, List<Filter>> currentlyOpenedFilters = filtersRepository.getCurrentlyOpenedFilters();
    // Check if we need to re-save the recently opened filters (in case they were converted to the new format)
    for (File filterFile : filtersFiles) {
      String group = filterFile.getName();
      List<Filter> filters = currentlyOpenedFilters.getOrDefault(group, new ArrayList<>());
      boolean isLegacy = filters.stream().anyMatch(filter -> filter.wasLoadedFromLegacyFile);
      if (isLegacy) {
        Logger.info("Filter Group: " + group + " is using old file format. Re-save it");
        // Now that we checked, clear the legacy flag
        filters.forEach(filter -> filter.wasLoadedFromLegacyFile = false);
        // ... and save it
        saveFilters(group);
      }
    }

    view.configureFiltersList(currentlyOpenedFilters);
    // Call checkForUnsavedChanges to clear the 'unsaved changes' state
    checkForUnsavedChanges();

    // Set all the currently opened filters as the latest
    File[] lastFilterPaths = filtersRepository.getCurrentlyOpenedFilterFiles().values().toArray(new File[0]);
    userPrefs.setLastFilterPaths(lastFilterPaths);
    if (userPrefs.getRememberAppliedFilters()) {
      reapplyRememberedFilters();
      view.onAppliedFiltersRemembered();
    }
  }

  @Override
  public void loadLogs(File[] logFiles) {
    logLoader.loadLogs(logFiles);
  }

  @Override
  public void loadLogs(File[] logFiles, Charset charset) {
    logLoader.loadLogs(logFiles, charset);
  }

  @Override
  public void refreshLogsWithDifferentCharset(Charset charset) {
    logLoader.refreshLogsWithDifferentCharset(charset);
  }

  @Override
  public void refreshLogs() {
    logLoader.refreshLogs();
  }

  @Override
  public void saveFilteredLogs(File file) {
    if (filteredLogs.isEmpty()) {
      return;
    }

    try {
      BufferedWriter fileWriter = new BufferedWriter(new FileWriter(file));
      for (LogEntry entry : filteredLogs) {
        fileWriter.write(entry.toString());
        fileWriter.newLine();
      }
      fileWriter.close();
    } catch (IOException e) {
      view.showErrorMessage(e.getMessage());
    }
  }

  @Override
  public void applyFilters() {
    filterCoordinator.applyFilters();
  }

  @Override
  public void filterEdited(Filter filter) {
    filterCoordinator.filterEdited(filter);
  }

  @Override
  public void setAllFiltersApplied(String group, boolean isApplied) {
    filterCoordinator.setAllFiltersApplied(group, isApplied);
  }

  @Override
  public void setAllFiltersApplied(boolean isApplied) {
    filterCoordinator.setAllFiltersApplied(isApplied);
  }

  @Override
  public void setStreamAllowed(LogStream stream, boolean allowed) {
    if (allowedStreamsMap.isEmpty() || !allowedStreamsMap.containsKey(stream)) {
      throw new IllegalStateException("Stream " + stream + " is not available");
    }

    allowedStreamsMap.put(stream, allowed);
    updateFiltersContextInfo();
    cachedAllowedFilteredLogs.clear();
    cachedAllowedFilteredLogs.addAll(excludeNonAllowedStreams(filteredLogs));
    view.showFilteredLogs(cachedAllowedFilteredLogs);
  }

  private void updateFiltersContextInfo() {
    Set<LogStream> allowedStreams = new HashSet<>();
    for (Map.Entry<LogStream, Boolean> entry : allowedStreamsMap.entrySet()) {
      if (entry.getValue()) {
        allowedStreams.add(entry.getKey());
      }
    }

    forEachFilter(filter -> {
      Filter.ContextInfo filterTemporaryInfo = filter.getTemporaryInfo();
      if (filterTemporaryInfo != null) {
        filterTemporaryInfo.setAllowedStreams(allowedStreams);
      }
    });
  }

  @Override
  public boolean isStreamAllowed(LogStream stream) {
    if (allowedStreamsMap.isEmpty()) {
      throw new IllegalStateException("There are no streams available");
    }

    if (!allowedStreamsMap.containsKey(stream)) {
      throw new IllegalStateException("Stream " + stream + " is not available");
    }

    return allowedStreamsMap.get(stream);
  }

  @Override
  public void ignoreLogsBefore(int index) {
    if (index == logsRepository.getFirstVisibleLogIndex()) {
      // Nothing to do, return early
      return;
    }

    if (index >= logsRepository.getLastVisibleLogIndex()) {
      // This would ignore all logs
      view.showErrorMessage("Cannot set first visible log after last visible log");
      return;
    }

    logsRepository.setFirstVisibleLogIndex(index);
    view.showLogs(logsRepository.getCurrentlyOpenedLogs());
    applyFilters();
  }

  @Override
  public void ignoreLogsAfter(int index) {
    if (index == logsRepository.getLastVisibleLogIndex()) {
      // Nothing to do, return early
      return;
    }

    if (index <= logsRepository.getFirstVisibleLogIndex()) {
      // This would ignore all logs
      view.showErrorMessage("Cannot set last visible log before first visible log");
      return;
    }

    logsRepository.setLastVisibleLogIndex(index);
    view.showLogs(logsRepository.getCurrentlyOpenedLogs());
    applyFilters();
  }

  @Override
  public void resetIgnoredLogs(boolean resetStarting, boolean resetEnding) {
    if (resetStarting) {
      logsRepository.setFirstVisibleLogIndex(-1);
    }
    if (resetEnding) {
      logsRepository.setLastVisibleLogIndex(-1);
    }

    view.showLogs(logsRepository.getCurrentlyOpenedLogs());
    applyFilters();
  }

  @Override
  public int getVisibleLogsOffset() {
    return logsRepository.getFirstVisibleLogIndex();
  }

  @Override
  public LogEntry getFirstVisibleLog() {
    int index = logsRepository.getFirstVisibleLogIndex();
    if (index <= 0) {
      // If the first visible log index is the first index, then there are no ignored logs,
      // so, just return null
      return null;
    }

    // "currently opened logs" already represents the visible logs, so just return the first one
    return logsRepository.getCurrentlyOpenedLogs().get(0);
  }

  @Override
  public LogEntry getLastVisibleLog() {
    int index = logsRepository.getLastVisibleLogIndex();
    int lastVisibleIndex = logsRepository.getCurrentlyOpenedLogs().size() - 1;
    if (index == (logsRepository.getAllLogsSize() - 1)) {
      // If the last visible log index is the last index, then there are no ignored logs,
      // so, just return null
      return null;
    }

    // "currently opened logs" already represents the visible logs, so just return the last one
    return logsRepository.getCurrentlyOpenedLogs().get(lastVisibleIndex);
  }

  @Override
  public void addLogEntriesToMyLogs(List<LogEntry> entries) {
    myLogsRepository.addLogEntries(entries);
    view.showMyLogs(myLogsRepository.getLogs());
  }

  @Override
  public void removeFromMyLog(int[] indices) {
    List<LogEntry> toRemove = new ArrayList<>();
    for (int i : indices) {
      int myLogsSize = myLogsRepository.getLogs().size();
      if (i < 0 || i >= myLogsSize) {
        Logger.warning("Trying to remove invalid index " + i + " from MyLogs. Current size: " + myLogsSize);
      } else {
        toRemove.add(myLogsRepository.getLogs().get(i));
      }
    }
    myLogsRepository.removeLogEntries(toRemove);
    view.showMyLogs(myLogsRepository.getLogs());
  }

  boolean updateMyLogs() {
    // We only want to update logs in 'My Logs' if the new logs that are being loaded are different
    // So we check if the logs under 'My Logs' are still matching the logs that are open. If not, clear
    // This is to avoid clearing 'My Logs' when the user is simply refreshing the logs (F5)
    if (myLogsRepository.getLogs().isEmpty()) {
      return false;
    }

    boolean mismatch = false;
    for (LogEntry myLogEntry : myLogsRepository.getLogs()) {
      // If myLog's index is greater than the number of new logs, it is a mismatch
      if (myLogEntry.getIndex() > logsRepository.getLastVisibleLogIndex()) {
        mismatch = true;
        break;
      }

      // If the myLog no longer matches the same index in the new logs, it is a mismatch
      List<LogEntry> currentLogs = logsRepository.getCurrentlyOpenedLogs();
      if (myLogEntry.getIndex() >= 0 && myLogEntry.getIndex() < currentLogs.size() &&
          !myLogEntry.equals(currentLogs.get(myLogEntry.getIndex()))) {
        mismatch = true;
        break;
      }
    }

    if (mismatch) {
      // It is a mismatch, the logs under 'My Logs' could still match the opened logs in a different position.
      // For example if the user opened the same log file again, but in addition with some other logs.
      // To cover this case, make sure all logs in 'MyLogs' have a match in the opened logs. And if so, update the
      // indices of the 'My Logs'
      List<LogEntry> matchedLogs = new ArrayList<>();

      for (LogEntry myLogEntry : myLogsRepository.getLogs()) {
        LogEntry matchedLogEntry = logsRepository.getMatchingLogEntry(myLogEntry);

        if (matchedLogEntry != null) {
          // Match found, update the 'My Logs' entry
          matchedLogs.add(matchedLogEntry);
        }
      }

      // Now, we always want to update the log entries in 'MyLogs' to have the correct indices so, remove all and
      // reinsert the ones that have a match
      myLogsRepository.reset(matchedLogs);
      return true;
    }

    return false;
  }

  private List<LogEntry> excludeNonAllowedStreams(List<LogEntry> entries) {
    if (allowedStreamsMap.isEmpty()) {
      // If there is no stream restriction just work with all entries
      return entries;
    }

    ArrayList<LogEntry> result = new ArrayList<>();
    Set<LogStream> allowedStreams = new HashSet<>();
    for (Map.Entry<LogStream, Boolean> entry : allowedStreamsMap.entrySet()) {
      if (entry.getValue()) {
        allowedStreams.add(entry.getKey());
      }
    }

    for (LogEntry entry : entries) {
      if (allowedStreams.contains(entry.getStream())) {
        result.add(entry);
      }
    }

    return result;
  }

  @Override
  public void finishing() {
    boolean shouldFinish = requestSaveUnsavedGroups();
    if (shouldFinish) {
      if (userPrefs.getRememberAppliedFilters()) {
        // Remember which filters are applied for the current files, so the next
        // time these files are opened, we can re-apply the same filters
        rememberAppliedFilters();
      }

      view.finish();
      release();
    }
  }

  private boolean requestSaveUnsavedGroups() {
    // 'unsavedFilterGroups' can be changed while we are iterating over it
    // create an array with its elements to be safe instead
    String[] unsavedGroups = unsavedFilterGroups.toArray(new String[0]);
    if (unsavedGroups.length == 0) {
      // There is no unsaved groups, return early. don't bother showing anything to the user
      return true;
    }

    Boolean[] groupsSelection = view.showAskToSaveMultipleFiltersDialog(unsavedGroups);
    if (groupsSelection != null) {
      for (int i = 0; i < unsavedGroups.length; i++) {
        // Check each group if user selected to save
        if (groupsSelection[i]) {
          saveFilters(unsavedGroups[i]);
        }
      }
      return true;
    }

    return false;
  }

  private void checkForUnsavedChanges() {
    unsavedFilterGroups.clear();
    List<String> changedGroups = filtersRepository.getChangedGroupsSinceLastOpened();
    Set<String> allGroups = filtersRepository.getCurrentlyOpenedFilters().keySet();

    for (String group : allGroups) {
      if (changedGroups.contains(group)) {
        view.showUnsavedFilterIndication(group);
        unsavedFilterGroups.add(group);
      } else {
        view.hideUnsavedFilterIndication(group);
      }
    }
  }

  private void forEachFilter(Consumer<Filter> consumer) {
    for (Map.Entry<String, List<Filter>> entry : filtersRepository.getCurrentlyOpenedFilters().entrySet()) {
      List<Filter> filtersFromGroup = entry.getValue();
      for (Filter f : filtersFromGroup) {
        consumer.accept(f);
      }
    }
  }

  private List<Filter> getFiltersThat(Predicate<Filter> condition) {
    List<Filter> resultFilters = new ArrayList<>();
    for (Map.Entry<String, List<Filter>> entry : filtersRepository.getCurrentlyOpenedFilters().entrySet()) {
      List<Filter> filtersFromGroup = entry.getValue();
      for (Filter f : filtersFromGroup) {
        if (condition.test(f)) {
          resultFilters.add(f);
        }
      }
    }

    return resultFilters;
  }

  private void rememberAppliedFilters() {
    testStats.rememberAppliedFiltersCallCount++;
    for (Map.Entry<String, List<Filter>> entry : filtersRepository.getCurrentlyOpenedFilters().entrySet()) {
      List<Filter> filtersFromGroup = entry.getValue();
      List<Integer> appliedIndices = new ArrayList<>();
      for (int i = 0; i < filtersFromGroup.size(); i++) {
        if (filtersFromGroup.get(i).isApplied()) {
          appliedIndices.add(i);
        }
        userPrefs.setAppliedFiltersIndices(entry.getKey(), appliedIndices);
      }
    }
  }

  private void reapplyRememberedFilters() {
    testStats.reapplyRememberedFiltersCallCount++;
    for (Map.Entry<String, List<Filter>> entry : filtersRepository.getCurrentlyOpenedFilters().entrySet()) {
      List<Integer> appliedIndices = userPrefs.getAppliedFiltersIndices(entry.getKey());

      if (!appliedIndices.isEmpty()) {
        List<Filter> filtersFromGroup = entry.getValue();
        for (int i = 0; i < filtersFromGroup.size(); i++) {
          if (appliedIndices.contains(i)) {
            filtersFromGroup.get(i).setApplied(true);
          }
        }
      }
    }

    applyFilters();
  }

  void updateProgress(int progress, String note) {
    updateAsyncProgress(progress, note);
  }

  // Test helpers
  static class Stats {
    int applyFiltersCallCount;
    int rememberAppliedFiltersCallCount;
    int reapplyRememberedFiltersCallCount;
  }

  final Stats testStats = new Stats();

  void setFilteredLogsForTesting(LogEntry[] filteredLogs) {
    setFilteredLogsForTesting(filteredLogs, false);
  }

  void setFilteredLogsForTesting(LogEntry[] filteredLogs, boolean setCached) {
    this.filteredLogs.clear();
    this.filteredLogs.addAll(Arrays.asList(filteredLogs));
    if (setCached) {
      this.cachedAllowedFilteredLogs.clear();
      this.cachedAllowedFilteredLogs.addAll(Arrays.asList(filteredLogs));
    }
  }

  void setAvailableStreamsForTesting(Set<LogStream> streams, boolean initiallyAllowed) {
    for (LogStream stream : streams) {
      allowedStreamsMap.put(stream, initiallyAllowed);
    }
  }

  void setAvailableStreamsForTesting(Set<LogStream> streams) {
    setAvailableStreamsForTesting(streams, false);
  }

  void setUnsavedGroupForTesting(String group) {
    unsavedFilterGroups.add(group);
  }
}
