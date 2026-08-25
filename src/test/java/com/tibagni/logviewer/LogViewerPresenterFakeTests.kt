package com.tibagni.logviewer

import com.tibagni.logviewer.fakes.FakeLogViewerPreferences
import com.tibagni.logviewer.fakes.FakeFiltersRepository
import com.tibagni.logviewer.fakes.FakeLogsRepository
import com.tibagni.logviewer.fakes.FakeMyLogsRepository
import com.tibagni.logviewer.filter.Filter
import com.tibagni.logviewer.log.LogLevel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import java.awt.Color
import java.io.File

/**
 * Integration and unit tests validating [LogViewerPresenterImpl] behaviors using state-based fakes.
 *
 * This test suite eliminates brittle mock setup by using in-memory implementations of the
 * system's preferences and repositories, verifying real state changes instead of mock invocations.
 */
class LogViewerPresenterFakeTests {
  private lateinit var fakePrefs: FakeLogViewerPreferences
  private lateinit var fakeLogsRepository: FakeLogsRepository
  private lateinit var fakeFiltersRepository: FakeFiltersRepository
  private lateinit var fakeMyLogsRepository: FakeMyLogsRepository

  @Mock
  private lateinit var mockView: LogViewerPresenterView

  private lateinit var presenter: LogViewerPresenterImpl

  /**
   * Returns Mockito.any() as nullable type to avoid java.lang.IllegalStateException when
   * null is returned.
   */
  private fun <T> anyOrNull(): T = org.mockito.Mockito.any()

  @Before
  fun setUp() {
    MockitoAnnotations.initMocks(this)
    fakePrefs = FakeLogViewerPreferences()
    fakeLogsRepository = FakeLogsRepository()
    fakeFiltersRepository = FakeFiltersRepository()
    fakeMyLogsRepository = FakeMyLogsRepository()

    presenter = LogViewerPresenterImpl(
      mockView,
      fakePrefs,
      fakeLogsRepository,
      fakeMyLogsRepository,
      fakeFiltersRepository
    )
    presenter.setBgExecutorService(MockExecutorService())
    presenter.setUiExecutor { it.run() }
  }

  /**
   * Verifies that when [FakeLogViewerPreferences.openLastFilter] is false,
   * presenter initialization skips attempting to load filters.
   */
  @Test
  fun testInitNotLoadingLastFilter() {
    fakePrefs.openLastFilter = false
    presenter.init()

    verify(mockView, never()).configureFiltersList(anyOrNull())
  }

  /**
   * Verifies that when [FakeLogViewerPreferences.openLastFilter] is true but no path is saved,
   * presenter initialization skips trying to parse files.
   */
  @Test
  fun testInitLoadingLastFilterNoFilterAvailable() {
    fakePrefs.openLastFilter = true
    fakePrefs.rememberAppliedFilters = false
    fakePrefs.lastFilterPaths = emptyArray()
    presenter.init()

    verify(mockView, never()).configureFiltersList(anyOrNull())
  }

  /**
   * Verifies that when a last filter path exists, presenter initialization automatically loads
   * and opens the filters in the repository and updates the UI filters list.
   */
  @Test
  fun testInitLoadingLastFilter() {
    val file = File("last_filter.txt")
    fakePrefs.openLastFilter = true
    fakePrefs.rememberAppliedFilters = false
    fakePrefs.lastFilterPaths = arrayOf(file)

    presenter.init()

    assertTrue(fakeFiltersRepository.currentlyOpenedFilterFiles.containsKey("last_filter.txt"))
    verify(mockView).configureFiltersList(fakeFiltersRepository.currentlyOpenedFilters)
  }

  /**
   * Verifies that calling [LogViewerPresenterImpl.addFilter] correctly updates the filter group
   * state in the repository and instructs the view to render the new list.
   */
  @Test
  fun testAddFilter() {
    val filter = Filter("Name", "Pattern", Color.BLUE, LogLevel.DEBUG)
    fakePrefs.reapplyFiltersAfterEdit = false

    presenter.addGroup("Group1")
    presenter.addFilter("Group1", filter)

    val openedFilters = fakeFiltersRepository.currentlyOpenedFilters
    assertTrue(openedFilters.containsKey("Group1"))
    assertEquals(1, openedFilters["Group1"]?.size)
    assertEquals(filter, openedFilters["Group1"]?.first())
    verify(mockView, atLeastOnce()).configureFiltersList(openedFilters)
  }

  /**
   * Verifies that removing a filter group correctly deletes it and its elements from the active
   * memory maps.
   */
  @Test
  fun testRemoveGroup() {
    val groupToRemove = "removeGroup"
    val testGroup = "testGroup"
    val filter = Filter("Name", "Pattern", Color.BLUE, LogLevel.DEBUG)

    fakeFiltersRepository.addGroup(testGroup)
    fakeFiltersRepository.addGroup(groupToRemove)
    fakeFiltersRepository.addFilter(groupToRemove, filter)

    presenter.removeGroup(groupToRemove)

    assertFalse(fakeFiltersRepository.currentlyOpenedFilters.containsKey(groupToRemove))
    assertTrue(fakeFiltersRepository.currentlyOpenedFilters.containsKey(testGroup))
  }

  /**
   * Verifies that reordering filters inside a group correctly mutates the element sequence
   * in the repository and forces a visual layout update.
   */
  @Test
  fun testReorderFilters() {
    val filter1 = Filter("Name1", "Pattern1", Color.BLUE, LogLevel.DEBUG)
    val filter2 = Filter("Name2", "Pattern2", Color.RED, LogLevel.DEBUG)

    fakeFiltersRepository.addGroup("Group1")
    fakeFiltersRepository.addFilter("Group1", filter1)
    fakeFiltersRepository.addFilter("Group1", filter2)

    presenter.reorderFilters("Group1", 0, 1)

    val filters = fakeFiltersRepository.currentlyOpenedFilters["Group1"]
    assertEquals(filter2, filters?.get(0))
    assertEquals(filter1, filters?.get(1))
    verify(mockView).configureFiltersList(fakeFiltersRepository.currentlyOpenedFilters)
  }
}
