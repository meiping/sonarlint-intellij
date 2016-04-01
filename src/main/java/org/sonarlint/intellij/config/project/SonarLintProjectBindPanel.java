/**
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.config.project;

import com.intellij.ide.DataManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.options.ex.Settings;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ComboBoxWithWidePopup;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.CheckForNull;
import javax.swing.AbstractAction;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.border.Border;
import org.jetbrains.annotations.Nullable;
import org.sonarlint.intellij.config.global.SonarLintGlobalConfigurable;
import org.sonarlint.intellij.config.global.SonarQubeServer;
import org.sonarlint.intellij.core.ServerUpdateTask;
import org.sonarlint.intellij.core.SonarLintServerManager;
import org.sonarlint.intellij.util.ResourceLoader;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine;
import org.sonarsource.sonarlint.core.client.api.connected.GlobalUpdateStatus;
import org.sonarsource.sonarlint.core.client.api.connected.RemoteModule;
import org.sonarsource.sonarlint.core.client.api.connected.StateListener;

import static org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine.State;

public class SonarLintProjectBindPanel {
  private static final String SERVER_EMPTY_TEXT = "<No servers configured>";
  private static final String PROJECT_EMPTY_TEXT = "<No project available>";
  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yy HH:MM");

  private JPanel rootPanel;
  private JPanel serverPanel;
  private JPanel bindPanel;
  private JComboBox<SonarQubeServer> serverComboBox;
  private JComboBox<RemoteModule> projectComboBox;
  private JButton resetButton;
  private JLabel serverStatus;
  private JButton updateServerButton;
  private ConnectedSonarLintEngine engine;
  private StateListener serverStateListener;
  private ProjectItemListener projectListener;

  private String selectedProjectKey;

  public JPanel create() {
    rootPanel = new JPanel(new GridBagLayout());
    createServerList();
    createServerStatus();
    createBindPane();

    rootPanel.add(serverPanel, new GridBagConstraints(0, 0, 1, 1, 1, 0, GridBagConstraints.FIRST_LINE_START, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 0), 0, 0));
    rootPanel.add(bindPanel, new GridBagConstraints(0, 1, 1, 1, 1, 1, GridBagConstraints.FIRST_LINE_START, GridBagConstraints.HORIZONTAL, new Insets(0, 4, 4, 0), 0, 0));

    return rootPanel;
  }

  public void load(Collection<SonarQubeServer> servers, @Nullable String selectedServerId, @Nullable String selectedProjectKey) {
    this.selectedProjectKey = selectedProjectKey;
    resetButton.setEnabled(!servers.isEmpty());

    serverComboBox.removeAllItems();
    setServerList(servers, selectedServerId);

    // ensure this is called, even when nothing is selected because there are no servers
    if (servers.isEmpty()) {
      onServerSelected(null);
    }

    serverComboBox.invalidate();
    serverComboBox.updateUI();
  }

  @CheckForNull
  public String getSelectedStorageId() {
    // do things in a type safe way
    int idx = serverComboBox.getSelectedIndex();
    if (idx < 0) {
      return null;
    }

    SonarQubeServer server = serverComboBox.getModel().getElementAt(idx);
    return server.getName();
  }

  @CheckForNull
  public String getSelectedProjectKey() {
    return selectedProjectKey;
  }

  /**
   * Updates server status label. If no server is selected, some components get disabled.
   * Should be called when selected Server changes.
   */
  private void onServerSelected(@Nullable final String selectedId) {
    SonarLintServerManager core = ApplicationManager.getApplication().getComponent(SonarLintServerManager.class);

    if (engine != null) {
      engine.removeStateListener(serverStateListener);
    }

    if (selectedId == null) {
      engine = null;
      serverStateListener = null;
      serverStatus.setText("[ no server ]");
      updateServerButton.setEnabled(false);
    } else {
      // assuming that server ID is valid!
      engine = core.getConnectedEngine(selectedId);

      serverStatus.setText(serverStatusString(engine));
      serverStateListener = new ServerStateListener();
      engine.addStateListener(serverStateListener);
      updateServerButton.setEnabled(true);
    }
    setProjects();
  }

  private void setProjects() {
    // temporally disable listener while we redo list and reselect
    projectComboBox.removeItemListener(projectListener);
    projectComboBox.removeAllItems();

    if (engine != null && engine.getState() == State.UPDATED) {
      Map<String, RemoteModule> moduleMap = engine.allModulesByKey();
      Set<RemoteModule> orderedSet = new TreeSet<>(new Comparator<RemoteModule>() {
        @Override public int compare(RemoteModule o1, RemoteModule o2) {
          int c1 = o1.getName().compareTo(o2.getName());
          if (c1 != 0) {
            return c1;
          }

          return o1.getKey().compareTo(o2.getKey());
        }
      });
      orderedSet.addAll(moduleMap.values());

      RemoteModule selected = null;
      for (RemoteModule mod : orderedSet) {
        projectComboBox.addItem(mod);
        if (selectedProjectKey != null && selectedProjectKey.equals(mod.getKey())) {
          selected = mod;
        }
      }

      projectComboBox.setSelectedItem(selected);
      projectComboBox.addItemListener(projectListener);
      projectComboBox.setPrototypeDisplayValue(null);
      projectComboBox.setEnabled(true);
    } else {
      // either no server selected, or not updated
      RemoteModule empty = new RemoteModule() {
        @Override public String getKey() {
          return "";
        }

        @Override public String getName() {
          return PROJECT_EMPTY_TEXT;
        }
      };
      projectComboBox.setPrototypeDisplayValue(empty);
      projectComboBox.setEnabled(false);
    }
  }

  /**
   * Sets new servers in the combo box, or disable it if there aren't any.
   * Will also enable or disable other components.
   */
  private void setServerList(Collection<SonarQubeServer> servers, @Nullable String selectedId) {
    if (servers.isEmpty()) {
      serverComboBox.setEnabled(false);
      SonarQubeServer s = new SonarQubeServer();
      s.setName(SERVER_EMPTY_TEXT);
      serverComboBox.setPrototypeDisplayValue(s);
    } else {
      serverComboBox.setEnabled(true);
      int i = 0;
      int selectedIndex = -1;
      for (SonarQubeServer s : servers) {
        if (selectedId != null && s.getName() != null && selectedId.equals(s.getName())) {
          selectedIndex = i;
        }
        serverComboBox.setPrototypeDisplayValue(null);
        serverComboBox.addItem(s);
        i++;
      }

      if (selectedIndex >= 0) {
        serverComboBox.setSelectedIndex(selectedIndex);
      }
    }
  }

  private static String serverStatusString(ConnectedSonarLintEngine engine) {
    StringBuilder builder = new StringBuilder();
    State state = engine.getState();
    GlobalUpdateStatus updateStatus = null;

    switch (state) {
      case NEVER_UPDATED:
        builder.append("never updated");
        break;
      case UPDATED:
        builder.append("updated");
        updateStatus = engine.getUpdateStatus();
        break;
      case UPDATING:
        builder.append("updating..");
        break;
      case UNKNOW:
      default:
        builder.append("unknown");
        break;
    }

    if (updateStatus != null) {
      builder.append(" [ version: ")
        .append(updateStatus.getServerVersion())
        .append(", updated: ")
        .append(DATE_FORMAT.format(updateStatus.getLastUpdateDate()))
        .append(" ]");
    }

    return builder.toString();
  }

  private void createBindPane() {
    projectListener = new ProjectItemListener();
    Border b = IdeBorderFactory.createTitledBorder("Project binding");
    bindPanel = new JPanel(new GridBagLayout());
    bindPanel.setBorder(b);
    projectComboBox = new ComboBoxWithWidePopup(new DefaultComboBoxModel<RemoteModule>());
    projectComboBox.setRenderer(new ProjectComboBoxRenderer());
    projectComboBox.setEditable(false);
    projectComboBox.setMinimumSize(new Dimension(10, 10));

    resetButton = new JButton();
    resetButton.setAction(new AbstractAction() {
      @Override public void actionPerformed(ActionEvent e) {
        actionClearAllBindings();
      }
    });
    resetButton.setText("Clear binding");

    bindPanel.add(projectComboBox, new GridBagConstraints(0, 0, 1, 1, 1, 0, GridBagConstraints.LINE_START, GridBagConstraints.HORIZONTAL, new Insets(0, 0, 0, 0), 0, 0));
    bindPanel.add(resetButton, new GridBagConstraints(0, 1, 1, 1, 1, 1, GridBagConstraints.LINE_START, GridBagConstraints.NONE, new Insets(10, 10, 0, 0), 0, 0));
  }

  /**
   * Create server status components without setting data
   */
  private void createServerStatus() {
    updateServerButton = new JButton();
    JLabel serverStatusLabel = new JLabel("Local storage status: ");
    serverStatus = new JLabel();

    serverPanel.add(serverStatusLabel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
      GridConstraints.SIZEPOLICY_CAN_SHRINK, GridConstraints.SIZEPOLICY_FIXED, null, serverStatusLabel.getSize(), null, 0, false));
    serverPanel.add(serverStatus, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
      GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    serverPanel.add(updateServerButton, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
      GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

    updateServerButton.setAction(new AbstractAction() {
      @Override public void actionPerformed(ActionEvent e) {
        actionUpdateServerTask();
      }
    });
    updateServerButton.setText("Update local configuration");
    updateServerButton.setToolTipText("Update local data: quality profile, settings, ...");
  }

  /**
   * Creates server list and associated buttons/labels, without setting data
   */
  private void createServerList() {
    serverPanel = new JPanel(new GridLayoutManager(2, 3));
    Border b = IdeBorderFactory.createTitledBorder("SonarQube server");
    serverPanel.setBorder(b);
    JButton configureServerButton = new JButton();
    serverComboBox = new ComboBox();
    JLabel serverListLabel = new JLabel("Connect to server:");

    serverComboBox.setRenderer(new ServerComboBoxRenderer());
    serverComboBox.addItemListener(new ServerItemListener());

    serverListLabel.setLabelFor(serverComboBox);

    serverPanel.add(serverListLabel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
      GridConstraints.SIZEPOLICY_CAN_SHRINK, GridConstraints.SIZEPOLICY_FIXED, null, serverListLabel.getSize(), null, 0, false));
    serverPanel.add(serverComboBox, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
      GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    serverPanel.add(configureServerButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
      GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

    configureServerButton.setAction(new AbstractAction() {
      @Override public void actionPerformed(ActionEvent e) {
        configureServers();
      }
    });
    configureServerButton.setText("Configure...");
  }

  /**
   * Navigates to global configuration. There are 2 possible ways of doing it, depending on how the settings are opened.
   */
  private void configureServers() {
    Settings allSettings = Settings.KEY.getData(DataManager.getInstance().getDataContext());
    if (allSettings != null) {
      final SonarLintGlobalConfigurable globalConfigurable = allSettings.find(SonarLintGlobalConfigurable.class);
      if (globalConfigurable != null) {
        allSettings.select(globalConfigurable);
      }
    } else {
      SonarLintGlobalConfigurable globalConfigurable = new SonarLintGlobalConfigurable();
      ShowSettingsUtil.getInstance().editConfigurable(rootPanel, globalConfigurable);
    }
  }

  private void actionClearAllBindings() {
    projectComboBox.setSelectedIndex(-1);
    selectedProjectKey = null;
  }

  private void actionUpdateServerTask() {
    // do things in a type safe way
    int idx = serverComboBox.getSelectedIndex();
    if (idx < 0) {
      return;
    }

    SonarQubeServer server = serverComboBox.getModel().getElementAt(idx);
    ServerUpdateTask task = new ServerUpdateTask(engine, server);
    ProgressManager.getInstance().run(task);
  }

  public void actionUpdateProjectTask() {
    // do things in a type safe way
    int idx = serverComboBox.getSelectedIndex();
    if (idx < 0) {
      return;
    }

    SonarQubeServer server = serverComboBox.getModel().getElementAt(idx);
    String projectKey = getSelectedProjectKey();
    ServerUpdateTask task = new ServerUpdateTask(engine, server, projectKey, true);
    ProgressManager.getInstance().run(task);
  }

  /**
   * Render SonarQube server in combo box
   */
  private static class ServerComboBoxRenderer extends ColoredListCellRenderer<SonarQubeServer> {
    @Override protected void customizeCellRenderer(JList list, SonarQubeServer value, int index, boolean selected, boolean hasFocus) {
      if (list.getModel().getSize() == 0) {
        append(SERVER_EMPTY_TEXT, SimpleTextAttributes.ERROR_ATTRIBUTES);
        return;
      }

      append(value.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, true);
      setToolTipText("Connect to this SonarQube server");
      try {
        setIcon(ResourceLoader.getIcon(ResourceLoader.ICON_SONARQUBE_16));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Render modules in combo box
   */
  private static class ProjectComboBoxRenderer extends ColoredListCellRenderer<RemoteModule> {
    @Override protected void customizeCellRenderer(JList list, RemoteModule value, int index, boolean selected, boolean hasFocus) {
      if (list.getModel().getSize() == 0) {
        append(PROJECT_EMPTY_TEXT, SimpleTextAttributes.ERROR_ATTRIBUTES);
        return;
      }

      if (value == null) {
        return;
      }

      append(value.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, true);
      // it is not working: appendTextPadding
      append(" ");
      if (index >= 0) {
        append("(" + value.getKey() + ")", SimpleTextAttributes.GRAY_ATTRIBUTES, false);
      }
    }
  }

  private class ServerItemListener implements  ItemListener {
    @Override
    public void itemStateChanged(ItemEvent event) {
      if (event.getStateChange() == ItemEvent.SELECTED) {
        onServerSelected(getSelectedStorageId());
      }
    }
  };

  private class ProjectItemListener implements ItemListener {
    @Override
    public void itemStateChanged(ItemEvent event) {
      if (event.getStateChange() == ItemEvent.SELECTED) {
        // do things in a type safe way
        int idx = projectComboBox.getSelectedIndex();
        if (idx < 0) {
          selectedProjectKey = null;
        } else {
          RemoteModule module = projectComboBox.getModel().getElementAt(idx);
          selectedProjectKey = module.getKey();
        }
      }
    }
  }

  private class ServerStateListener implements StateListener {
    @Override public void stateChanged(State newState) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override public void run() {
          serverStatus.setText(serverStatusString(engine));
          if(engine.getState() == State.UPDATED) {
            setProjects();
          }
        }
      });
    }
  }
}