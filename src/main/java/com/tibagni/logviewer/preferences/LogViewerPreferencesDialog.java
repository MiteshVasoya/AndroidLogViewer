package com.tibagni.logviewer.preferences;

import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import com.tibagni.logviewer.ServiceLocator;
import com.tibagni.logviewer.theme.LogViewerThemeManager;
import com.tibagni.logviewer.util.scaling.UIScaleUtils;
import com.tibagni.logviewer.util.layout.GBConstraintsBuilder;
import com.tibagni.logviewer.view.ButtonsPane;
import com.tibagni.logviewer.view.JFileChooserExt;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

/**
 * Preferences configuration dialog for LogViewer application.
 *
 * This dialog implements a decomposed panel structure to resolve layout cognitive overload (R1)
 * and prevent change propagation side-effects (R2) when preferences are added or updated.
 */
public class LogViewerPreferencesDialog extends JDialog implements ButtonsPane.Listener {

  private ButtonsPane buttonsPane;
  private JPanel contentPane;

  private AppearancePanel appearancePanel;
  private FoldersPanel foldersPanel;
  private BehaviorPanel behaviorPanel;
  private ExternalToolsPanel externalToolsPanel;

  private final LogViewerPreferences userPrefs;
  private final LogViewerThemeManager themeManager;

  public LogViewerPreferencesDialog(JFrame owner) {
    super(owner);
    userPrefs = ServiceLocator.INSTANCE.getLogViewerPrefs();
    themeManager = ServiceLocator.INSTANCE.getThemeManager();

    buildUi();
    setContentPane(contentPane);
    setModal(true);
    buttonsPane.setDefaultButtonOk();

    // Adjust the size according to the content after everything is populated
    contentPane.setPreferredSize(contentPane.getPreferredSize());
    contentPane.validate();

    // call onCancel() when cross is clicked
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(new WindowAdapter() {
      public void windowClosing(WindowEvent e) {
        onCancel();
      }
    });

    // call onCancel() on ESCAPE
    contentPane.registerKeyboardAction(e -> onCancel(),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
  }

  @Override
  public void onOk() {
    appearancePanel.save();
    foldersPanel.save();
    behaviorPanel.save();
    externalToolsPanel.save();
    dispose();
  }

  @Override
  public void onCancel() {
    dispose();
  }

  public static void showPreferencesDialog(JFrame parent) {
    LogViewerPreferencesDialog dialog = new LogViewerPreferencesDialog(parent);
    dialog.pack();
    dialog.setLocationRelativeTo(parent);
    dialog.setVisible(true);
  }

  private void buildUi() {
    contentPane = new JPanel();
    contentPane.setLayout(new GridBagLayout());
    contentPane.setRequestFocusEnabled(true);
    contentPane.setBorder(BorderFactory.createEmptyBorder(
        UIScaleUtils.dip(10),
        UIScaleUtils.dip(10),
        UIScaleUtils.dip(10),
        UIScaleUtils.dip(10)));

    buttonsPane = new ButtonsPane(ButtonsPane.ButtonsMode.OK_CANCEL, this);
    contentPane.add(buttonsPane,
        new GBConstraintsBuilder()
            .withGridx(0)
            .withGridy(1)
            .withWeightx(1.0)
            .withFill(GridBagConstraints.BOTH)
            .build());

    JPanel composedForm = new JPanel();
    composedForm.setLayout(new BoxLayout(composedForm, BoxLayout.Y_AXIS));

    appearancePanel = new AppearancePanel(themeManager, userPrefs);
    foldersPanel = new FoldersPanel(userPrefs);
    behaviorPanel = new BehaviorPanel(userPrefs);
    externalToolsPanel = new ExternalToolsPanel(userPrefs);

    composedForm.add(appearancePanel);
    composedForm.add(Box.createVerticalStrut(UIScaleUtils.dip(8)));
    composedForm.add(foldersPanel);
    composedForm.add(Box.createVerticalStrut(UIScaleUtils.dip(8)));
    composedForm.add(behaviorPanel);
    composedForm.add(Box.createVerticalStrut(UIScaleUtils.dip(8)));
    composedForm.add(externalToolsPanel);

    contentPane.add(composedForm,
        new GBConstraintsBuilder()
            .withGridx(0)
            .withGridy(0)
            .withWeightx(1.0)
            .withWeighty(1.0)
            .withFill(GridBagConstraints.BOTH)
            .build());
  }

  /**
   * Panel encapsulating appearance and theme settings.
   *
   * Invariants:
   * - Look and feel options are populated directly from the application theme manager.
   * - Saves user selection only when {@link #save()} is invoked.
   */
  private static class AppearancePanel extends JPanel {
    private final JComboBox<String> lookAndFeelCbx;
    private final LogViewerThemeManager themeManager;
    private final LogViewerPreferences userPrefs;

    /**
     * Constructs the appearance panel.
     *
     * Pre-conditions:
     * - themeManager must not be null.
     * - userPrefs must not be null.
     */
    AppearancePanel(LogViewerThemeManager themeManager, LogViewerPreferences userPrefs) {
      this.themeManager = themeManager;
      this.userPrefs = userPrefs;
      setBorder(BorderFactory.createTitledBorder("Appearance"));
      setLayout(new FormLayout("fill:d:grow,left:4dlu:noGrow,fill:d:grow", "center:d:grow"));

      CellConstraints cc = new CellConstraints();
      JLabel lookNFeelLbl = new JLabel("Look And Feel:");
      add(lookNFeelLbl, cc.xy(1, 1));

      lookAndFeelCbx = new JComboBox<>();
      lookAndFeelCbx.setMinimumSize(new Dimension());
      for (String theme : themeManager.getAvailableThemes()) {
        lookAndFeelCbx.addItem(theme);
      }
      lookAndFeelCbx.setSelectedItem(themeManager.getCurrentTheme());
      add(lookAndFeelCbx, cc.xy(3, 1));
    }

    /**
     * Persists the selected look and feel theme to preferences.
     *
     * Post-conditions:
     * - If a new theme is selected, it is saved to userPrefs.
     */
    void save() {
      String theme = (String) lookAndFeelCbx.getSelectedItem();
      if (theme != null && !theme.equals(themeManager.getCurrentTheme())) {
        userPrefs.setLookAndFeel(theme);
      }
    }
  }

  /**
   * Panel encapsulating default search and logs storage paths.
   *
   * Invariants:
   * - Log and filter folder selections are cached in-memory and deferred until {@link #save()} is called.
   */
  private class FoldersPanel extends JPanel {
    private final JTextField logsPathTxt;
    private final JButton logsPathBtn;
    private final JTextField filtersPathTxt;
    private final JButton filtersPathBtn;
    private final LogViewerPreferences userPrefs;

    private File selectedLogsFolder;
    private File selectedFiltersFolder;

    private JFileChooser logsFolderChooser;
    private JFileChooser filterFolderChooser;

    /**
     * Constructs the folders panel.
     *
     * Pre-conditions:
     * - userPrefs must not be null.
     */
    FoldersPanel(LogViewerPreferences userPrefs) {
      this.userPrefs = userPrefs;
      setBorder(BorderFactory.createTitledBorder("Default Folders"));
      setLayout(new FormLayout(
          "fill:d:grow,left:4dlu:noGrow,fill:d:grow,left:4dlu:noGrow,fill:d:grow",
          "center:d:grow,top:3dlu:noGrow,center:d:grow"));

      CellConstraints cc = new CellConstraints();

      JLabel defaultLogsLbl = new JLabel("Default path for log files:");
      add(defaultLogsLbl, cc.xy(1, 1));
      logsPathTxt = new JTextField(userPrefs.getDefaultLogsPath().getAbsolutePath());
      logsPathTxt.setEditable(false);
      add(logsPathTxt, cc.xy(3, 1, CellConstraints.FILL, CellConstraints.DEFAULT));
      logsPathBtn = new JButton("...");
      logsPathBtn.addActionListener(e -> onSelectLogsPath());
      add(logsPathBtn, cc.xy(5, 1));

      JLabel defaultFiltersLbl = new JLabel("Default path for filter files:");
      add(defaultFiltersLbl, cc.xy(1, 3));
      filtersPathTxt = new JTextField(userPrefs.getDefaultFiltersPath().getAbsolutePath());
      filtersPathTxt.setEditable(false);
      add(filtersPathTxt, cc.xy(3, 3, CellConstraints.FILL, CellConstraints.DEFAULT));
      filtersPathBtn = new JButton("...");
      filtersPathBtn.addActionListener(e -> onSelectFilterPath());
      add(filtersPathBtn, cc.xy(5, 3));
    }

    private void onSelectLogsPath() {
      if (logsFolderChooser == null) {
        logsFolderChooser = new JFileChooserExt(userPrefs.getDefaultLogsPath());
        logsFolderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      }

      int selectedOption = logsFolderChooser.showOpenDialog(LogViewerPreferencesDialog.this);
      if (selectedOption == JFileChooser.APPROVE_OPTION) {
        selectedLogsFolder = logsFolderChooser.getSelectedFile();
        logsPathTxt.setText(selectedLogsFolder.getAbsolutePath());
      }
    }

    private void onSelectFilterPath() {
      if (filterFolderChooser == null) {
        filterFolderChooser = new JFileChooserExt(userPrefs.getDefaultFiltersPath());
        filterFolderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      }

      int selectedOption = filterFolderChooser.showOpenDialog(LogViewerPreferencesDialog.this);
      if (selectedOption == JFileChooser.APPROVE_OPTION) {
        selectedFiltersFolder = filterFolderChooser.getSelectedFile();
        filtersPathTxt.setText(selectedFiltersFolder.getAbsolutePath());
      }
    }

    /**
     * Persists selected folder locations to user preferences.
     *
     * Post-conditions:
     * - Configured logs and filters directories are updated in userPrefs.
     */
    void save() {
      if (selectedLogsFolder != null) {
        userPrefs.setDefaultLogsPath(selectedLogsFolder);
      }
      if (selectedFiltersFolder != null) {
        userPrefs.setDefaultFiltersPath(selectedFiltersFolder);
      }
    }
  }

  /**
   * Panel encapsulating feature toggles and core runtime behaviors.
   *
   * Invariants:
   * - Behavior settings correspond to direct boolean properties of user preferences.
   */
  private static class BehaviorPanel extends JPanel {
    private final JCheckBox openLastFilterChbx;
    private final JCheckBox applyFiltersAfterEditChbx;
    private final JCheckBox rememberAppliedFiltersChbx;
    private final JCheckBox collapseAllGroupsStartup;
    private final JCheckBox showLineNumbersChbx;
    private final JCheckBox applyFiltersOnCheckChbx;
    private final LogViewerPreferences userPrefs;

    /**
     * Constructs the behavior panel.
     *
     * Pre-conditions:
     * - userPrefs must not be null.
     */
    BehaviorPanel(LogViewerPreferences userPrefs) {
      this.userPrefs = userPrefs;
      setBorder(BorderFactory.createTitledBorder("Behavior & Features"));
      setLayout(new FormLayout(
          "fill:d:grow,left:4dlu:noGrow,fill:d:grow",
          "center:d:grow,top:3dlu:noGrow,center:d:grow,top:3dlu:noGrow,center:d:grow,top:3dlu:noGrow,center:d:grow,top:3dlu:noGrow,center:d:grow,top:3dlu:noGrow,center:d:grow"));

      CellConstraints cc = new CellConstraints();

      JLabel openLastLbl = new JLabel("Open last filters on startup:");
      add(openLastLbl, cc.xy(1, 1));
      openLastFilterChbx = new JCheckBox();
      openLastFilterChbx.setSelected(userPrefs.getOpenLastFilter());
      add(openLastFilterChbx, cc.xy(3, 1));

      JLabel applyFiltersLbl = new JLabel("Apply filters after edit:");
      add(applyFiltersLbl, cc.xy(1, 3));
      applyFiltersAfterEditChbx = new JCheckBox();
      applyFiltersAfterEditChbx.setSelected(userPrefs.getReapplyFiltersAfterEdit());
      add(applyFiltersAfterEditChbx, cc.xy(3, 3));

      JLabel rememberFiltersLbl = new JLabel("Remember applied filters:");
      add(rememberFiltersLbl, cc.xy(1, 5));
      rememberAppliedFiltersChbx = new JCheckBox();
      rememberAppliedFiltersChbx.setSelected(userPrefs.getRememberAppliedFilters());
      add(rememberAppliedFiltersChbx, cc.xy(3, 5));

      JLabel collapseOnStartLbl = new JLabel("Collapse all groups on startup:");
      add(collapseOnStartLbl, cc.xy(1, 7));
      collapseAllGroupsStartup = new JCheckBox();
      collapseAllGroupsStartup.setSelected(userPrefs.getCollapseAllGroupsStartup());
      add(collapseAllGroupsStartup, cc.xy(3, 7));

      JLabel showLineNumberLbl = new JLabel("Show Line numbers:");
      add(showLineNumberLbl, cc.xy(1, 9));
      showLineNumbersChbx = new JCheckBox();
      showLineNumbersChbx.setSelected(userPrefs.getShowLineNumbers());
      add(showLineNumbersChbx, cc.xy(3, 9));

      JLabel applyFiltersOnChangeLbl = new JLabel("Apply filters on check:");
      add(applyFiltersOnChangeLbl, cc.xy(1, 11));
      applyFiltersOnCheckChbx = new JCheckBox();
      applyFiltersOnCheckChbx.setSelected(userPrefs.getApplyFilterOnCheck());
      add(applyFiltersOnCheckChbx, cc.xy(3, 11));
    }

    /**
     * Persists all configured behavior checkboxes to preferences.
     *
     * Post-conditions:
     * - All boolean toggles are directly written to userPrefs.
     */
    void save() {
      userPrefs.setOpenLastFilter(openLastFilterChbx.isSelected());
      userPrefs.setReapplyFiltersAfterEdit(applyFiltersAfterEditChbx.isSelected());
      userPrefs.setRememberAppliedFilters(rememberAppliedFiltersChbx.isSelected());
      userPrefs.setCollapseAllGroupsStartup(collapseAllGroupsStartup.isSelected());
      userPrefs.setShowLineNumbers(showLineNumbersChbx.isSelected());
      userPrefs.setApplyFilterOnCheck(applyFiltersOnCheckChbx.isSelected());
    }
  }

  /**
   * Panel encapsulating preferred external text editors.
   *
   * Invariants:
   * - Holds a deferred reference to the selected text editor executable path.
   */
  private class ExternalToolsPanel extends JPanel {
    private final JTextField preferredEditorPathTxt;
    private final JButton preferredEditorPathBtn;
    private final LogViewerPreferences userPrefs;

    private File selectedEditorFile;
    private JFileChooser preferredEditorFileChooser;

    /**
     * Constructs the external tools panel.
     *
     * Pre-conditions:
     * - userPrefs must not be null.
     */
    ExternalToolsPanel(LogViewerPreferences userPrefs) {
      this.userPrefs = userPrefs;
      setBorder(BorderFactory.createTitledBorder("External Tools"));
      setLayout(new FormLayout(
          "fill:d:grow,left:4dlu:noGrow,fill:d:grow,left:4dlu:noGrow,fill:d:grow",
          "center:d:grow"));

      CellConstraints cc = new CellConstraints();

      JLabel preferredEditorLbl = new JLabel("Preferred text Editor:");
      add(preferredEditorLbl, cc.xy(1, 1));
      File editorFile = userPrefs.getPreferredTextEditor();
      String path = editorFile != null ? editorFile.getAbsolutePath() : "";
      preferredEditorPathTxt = new JTextField(path);
      preferredEditorPathTxt.setEditable(false);
      add(preferredEditorPathTxt, cc.xy(3, 1, CellConstraints.FILL, CellConstraints.DEFAULT));
      preferredEditorPathBtn = new JButton("...");
      preferredEditorPathBtn.addActionListener(e -> onSelectPreferredEditorPath());
      add(preferredEditorPathBtn, cc.xy(5, 1));
    }

    private void onSelectPreferredEditorPath() {
      if (preferredEditorFileChooser == null) {
        preferredEditorFileChooser = new JFileChooserExt(userPrefs.getPreferredTextEditor());
      }

      int selectedOption = preferredEditorFileChooser.showOpenDialog(LogViewerPreferencesDialog.this);
      if (selectedOption == JFileChooser.APPROVE_OPTION) {
        selectedEditorFile = preferredEditorFileChooser.getSelectedFile();
        preferredEditorPathTxt.setText(selectedEditorFile.getAbsolutePath());
      }
    }

    /**
     * Persists the selected external editor path to preferences.
     *
     * Post-conditions:
     * - Writes the selected file executable reference to userPrefs.
     */
    void save() {
      if (selectedEditorFile != null) {
        userPrefs.setPreferredTextEditor(selectedEditorFile);
      }
    }
  }
}
