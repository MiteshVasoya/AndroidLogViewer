package com.tibagni.logviewer.filter;

import com.tibagni.logviewer.ProgressReporter;
import com.tibagni.logviewer.log.LogEntry;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class Filters {

  private static class Progress {
    public final long totalLogs;
    public final long publishThreshold;
    public final LongAdder logsRead = new LongAdder();
    public final AtomicLong logsReadOnProgressPublish = new AtomicLong(0);

    public Progress(long totalLogs) {
      this.totalLogs = totalLogs;
      this.publishThreshold = Math.max(1, totalLogs / 10);
    }
  }

  public static List<LogEntry> applyMultipleFilters(List<LogEntry> input, Filter[] filters, ProgressReporter pr) {
    initializeContextInfo(filters);
    // This algorithm is O(n*m), but we can assume the 'filters' array will only contain a few elements
    // So, in practice, this will be much closer to O(n) than O(nˆ2)
    List<LogEntry> filtered = new Vector<>();
    final Progress progress = new Progress(input.size());
    input.stream().parallel().forEach(entry -> {
      if (Thread.currentThread().isInterrupted()) {
        throw new RuntimeException(new InterruptedException("Filter application cancelled."));
      }
      Filter appliedFilter = getAppliedFilter(entry, filters);
      if (appliedFilter != null) {
        entry.setAppliedFilter(appliedFilter);
        filtered.add(entry);
      }

      // Increment progress atomically using LongAdder to prevent false-sharing on hot cache lines.
      progress.logsRead.increment();
      long currentLogsRead = progress.logsRead.sum();
      long lastPublished = progress.logsReadOnProgressPublish.get();
      if (currentLogsRead > (lastPublished + progress.publishThreshold)
              || currentLogsRead >= progress.totalLogs ) {
        // Atomic compareAndSet prevents multiple threads from triggering overlapping progress updates.
        if (progress.logsReadOnProgressPublish.compareAndSet(lastPublished, currentLogsRead)) {
          long total = progress.totalLogs > 0 ? progress.totalLogs : 1;
          pr.onProgress((int) (currentLogsRead * 100 / total), "Applying filters...");
        }
      }
    });
    Collections.sort(filtered);

    pr.onProgress(100, "Done!");
    return filtered;
  }

  private static void initializeContextInfo(Filter[] filters) {
    for (Filter filter : filters) {
      filter.initTemporaryInfo();
    }
  }

  private static Filter getAppliedFilter(LogEntry entry, Filter[] filters) {
    Filter firstFound = null;
    for (Filter filter : filters) {
      if (filter.appliesTo(entry)) {
        if (firstFound == null) {
          firstFound = filter;
        }

        // Increment the filter's 'linesFound' so we can show to the user
        // how many times each filter has matched
        filter.getTemporaryInfo().incrementLineCount(entry.getStream());
      }
    }

    return firstFound;
  }
}
