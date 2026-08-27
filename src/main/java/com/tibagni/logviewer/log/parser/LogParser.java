package com.tibagni.logviewer.log.parser;

import com.tibagni.logviewer.ProgressReporter;
import com.tibagni.logviewer.bugreport.parser.BugReportParser;
import com.tibagni.logviewer.log.*;
import com.tibagni.logviewer.logger.Logger;
import com.tibagni.logviewer.util.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser {
  // This is the maximum size of a payload log from Android
  private static final int LOGGER_ENTRY_MAX_PAYLOAD = 4068;
  // Even though Android limits its buffer for log payload to LOGGER_ENTRY_MAX_PAYLOAD
  // There are other parts of the log, like TAG, timestamp, pid, tid...
  // So, to be absolute sure we will not discard a valid log file because
  // of size restriction, set our maximum to twice the Android's payload size.
  public static final int MAX_LOG_LINE_ALLOWED = LOGGER_ENTRY_MAX_PAYLOAD * 2;

  private static final Pattern LOG_LEVEL_PATTERN =
      Pattern.compile("^\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}.*?([VDIWE])");
  private static final Pattern LOG_START_PATTERN_OBJ =
      Pattern.compile("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*");
  private static final Pattern LOG_TIMESTAMP_PATTERN =
      Pattern.compile("^(\\d{1,2})-(\\d{1,2})\\s(\\d{1,2}):(\\d{1,2}):(\\d{1,2}).(\\d{3,})");

  private LogReader logReader;
  private List<LogEntry> logEntries;
  private ProgressReporter progressReporter;
  private final List<String> logsSkipped;
  private final Map<String, String> potentialBugReports;

  public LogParser(LogReader logReader, ProgressReporter progressReporter) {
    this.logReader = logReader;
    this.progressReporter = progressReporter;
    this.logEntries = new ArrayList<>();
    this.logsSkipped = new ArrayList<>();
    this.potentialBugReports = new HashMap<>();
  }

  public LogEntry[] parseLogs(Charset charset) throws LogReaderException {
    ensureState();

    logReader.readLogs(charset);
    Set<String> availableLogs = logReader.getAvailableLogPaths();

    int logsRead = 0;
    for (String log : availableLogs) {
      if (Thread.currentThread().isInterrupted()) {
        throw new LogReaderException("Log parsing was cancelled.");
      }
      try {
        int progress = logsRead++ * 90 / availableLogs.size();
        progressReporter.onProgress(progress, "Reading " + log + "...");
        String logText = logReader.get(log);
        List<LogEntry> logEntriesFromFile = getLogEntries(logText, log);

        if (!logEntriesFromFile.isEmpty()) {
          logEntries.addAll(logEntriesFromFile);
        } else {
          Logger.warning("Skipping " + log + " because it was empty");
          logsSkipped.add(log);
        }
      } catch(Exception e) {
        Logger.warning("Skipping " + log + " because it failed to parse", e);
        logsSkipped.add(log);
      }
    }

    if (availableLogs.size() > 1) {
      progressReporter.onProgress(91, "Sorting...");
      Collections.sort(logEntries);
    }

    progressReporter.onProgress(95, "Setting index...");
    int index = 0;
    for (LogEntry entry : logEntries) {
      entry.setIndex(index++);
    }

    progressReporter.onProgress(100, "Completed");
    return logEntries.toArray(new LogEntry[0]);
  }

  @NotNull
  public List<String> getLogsSkipped() {
    return logsSkipped;
  }

  @NotNull
  public Map<String, String> getPotentialBugReports() {
    return potentialBugReports;
  }

  @NotNull
  public Set<LogStream> getAvailableStreams() {
    ensureState();

    Set<LogStream> availableStreams = new HashSet<>();
    Set<String> availableLogsNames = logReader.getAvailableLogPaths();
    for (String logName : availableLogsNames) {
      availableStreams.add(LogStream.inferLogStreamFromName(logName));
    }

    return availableStreams;
  }

  private void ensureState() {
    if (logReader == null || logEntries == null || progressReporter == null) {
      throw new IllegalStateException("LogParser was already released. Cannot use it...");
    }
  }

  public void release() {
    logEntries.clear();
    logEntries = null;

    progressReporter = null;

    logReader.close();
    logReader = null;
  }

  private List<LogEntry> getLogEntries(String logText, String logPath) {
    String[] lines = logText.split(StringUtils.LINE_SEPARATOR);
    List<LogEntry> logLines = new ArrayList<>(lines.length);

    StringBuilder currentLogLine = null;
    for (String line : lines) {
      if (Thread.currentThread().isInterrupted()) {
        throw new RuntimeException("Log parsing cancelled.");
      }

      line = sanitizeLine(line);

      if (isLogLine(line)) {
        if (currentLogLine != null) {
          logLines.add(createLogEntry(currentLogLine.toString(), logPath));
        }

        currentLogLine = new StringBuilder(line);
      } else if (!shouldIgnoreLine(line) && currentLogLine != null) {
        // This is probably a continuation of an already started log line. Append to it
        if (currentLogLine.length() >= MAX_LOG_LINE_ALLOWED) {
          handleLineOverflow(currentLogLine, logPath, logText, logLines);
          currentLogLine = null;

          // This could simply be a malformed line, just continue parsing other lines
          continue;
        }
        currentLogLine.append(StringUtils.LINE_SEPARATOR).append(line);
      }
    }

    // Make sure to add the last log line as well
    if (currentLogLine != null) {
      logLines.add(createLogEntry(currentLogLine.toString(), logPath));
    }

    return logLines;
  }

  /**
   * Sanitizes a log line by stripping any trailing null characters.
   *
   * @param line The raw log line to sanitize.
   * @return The sanitized log line without trailing nulls.
   */
  private String sanitizeLine(String line) {
    if (!line.isEmpty() && line.charAt(line.length() - 1) == '\u0000') {
      return line.replace("\u0000", "");
    }
    return line;
  }

  /**
   * Handles a log line that exceeds the maximum allowed length, logging warnings and
   * identifying potential bugreports as necessary.
   *
   * @param currentLogLine The builder holding the current log line payload. Must not be null.
   * @param logPath The filesystem path to the log file.
   * @param logText The entire file content text.
   * @param logLines The accumulated list of parsed LogEntry instances.
   */
  private void handleLineOverflow(StringBuilder currentLogLine, String logPath, String logText, List<LogEntry> logLines) {
    currentLogLine.delete(MAX_LOG_LINE_ALLOWED, currentLogLine.length());

    // First check if we have already considered this as a potential bugreport. If so,
    // don't waste any more time here
    if (!potentialBugReports.containsKey(logPath)) {
      String incorrectLinePreview = currentLogLine.substring(0, 100) + "...";
      Logger.warning(
          "Incorrect format on following line (too long - " + currentLogLine.length() + " bytes):\n" +
              "\"" + incorrectLinePreview + "\"\n\n" +
              "Maximum logcat line should be " + LOGGER_ENTRY_MAX_PAYLOAD + " bytes");

      // This could be a bugreport. If this is the case, keep track of it
      if (isPotentialBugReport(logText)) {
        Logger.info("Found a potential bugreport: " + logPath);

        // Make sure to remove all '\r' so it does not get in the way of the parsers
        String bugReportText = logText.replaceAll("\r", "");
        potentialBugReports.put(logPath, bugReportText);
      }
    }

    // We are done with this line, add it to the list
    logLines.add(createLogEntry(currentLogLine.toString(), logPath));
  }

  private LogEntry createLogEntry(String logLine, String logName) {
    return new LogEntry(logLine, findLogLevel(logLine), findTimestamp(logLine), logName);
  }

  LogLevel findLogLevel(String logLine) {
    LogLevel logLevel = LogLevel.DEBUG;

    Matcher matcher = LOG_LEVEL_PATTERN.matcher(logLine);
    if (matcher.find()) {
      logLevel = LogLevel.createFromStringLevel(matcher.group(1));
    }

    return logLevel;
  }

  LogTimestamp findTimestamp(String logLine) {
    LogTimestamp timestamp = null;

    try {
      Matcher matcher = LOG_TIMESTAMP_PATTERN.matcher(logLine);
      if (matcher.find()) {
        timestamp = new LogTimestamp(matcher.group(1),
            matcher.group(2),
            matcher.group(3),
            matcher.group(4),
            matcher.group(5),
            matcher.group(6));
      }
    } catch (Exception e) {
      // Don't add a timestamp if we couldn't parse it
      // This should never happen anyway
      Logger.error("Failed to parse timestamp for: " + logLine, e);
    }

    return timestamp;
  }

  private boolean isLogLine(String line) {
    return LOG_START_PATTERN_OBJ.matcher(line).matches();
  }

  private boolean shouldIgnoreLine(String line) {
    return line.startsWith("--------- beginning of");
  }

  private boolean isPotentialBugReport(String logText) {
    return BugReportParser.isBugReport(logText);
  }
}
