package com.tibagni.logviewer.log;

import java.awt.Color;

/**
 * Abstraction representing filter attributes needed by log rendering logic.
 *
 * This interface inverts package dependencies, allowing the low-level [log] package
 * to format and render highlights without direct compile-time coupling to the high-level
 * [filter] package.
 */
public interface LogEntryFilter {
  /**
   * The visual color assigned to the matched text or entry lines.
   */
  Color getColor();

  /**
   * The regex pattern or raw string pattern matching standard log content.
   */
  String getPatternString();

  /**
   * True if search parsing must match the casing of the pattern exactly.
   */
  boolean isCaseSensitive();
}
