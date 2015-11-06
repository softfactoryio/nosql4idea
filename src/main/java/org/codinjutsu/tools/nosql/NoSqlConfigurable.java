/*
 * Copyright (c) 2013 David Boissier
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codinjutsu.tools.nosql;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.BaseConfigurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.ui.*;
import com.intellij.ui.table.JBTable;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.LinkedList;
import java.util.List;

import static org.apache.commons.lang.StringUtils.isBlank;

public class NoSqlConfigurable extends BaseConfigurable {


    private final Project project;

    private final NoSqlConfiguration configuration;

    private final List<ServerConfiguration> configurations;
    private final DatabaseVendorManager databaseVendorManager;

    private JPanel mainPanel;
    private JBTable table;
    private final NoSqlServerTableModel tableModel;
    private ShellPathPanel mongoShellPanel;
    private ShellPathPanel redisShellPanel;


    public NoSqlConfigurable(Project project) {
        this.project = project;
        this.configuration = NoSqlConfiguration.getInstance(project);
        this.databaseVendorManager = DatabaseVendorManager.getInstance(project);
        configurations = new LinkedList<ServerConfiguration>(this.configuration.getServerConfigurations());
        tableModel = new NoSqlServerTableModel(configurations);
        mainPanel = new JPanel(new BorderLayout());
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "NoSql Servers";
    }

    @Nullable
    @Override
    public String getHelpTopic() {
        return "preferences.noSqlOptions";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        JPanel databaseVendorShellOptionsPanel = new JPanel();
        databaseVendorShellOptionsPanel.setLayout(new BoxLayout(databaseVendorShellOptionsPanel, BoxLayout.Y_AXIS));
        mongoShellPanel = new ShellPathPanel(DatabaseVendor.MONGO);
        databaseVendorShellOptionsPanel.add(mongoShellPanel);
        redisShellPanel = new ShellPathPanel(DatabaseVendor.REDIS);
        databaseVendorShellOptionsPanel.add(redisShellPanel);

        mainPanel.add(databaseVendorShellOptionsPanel, BorderLayout.NORTH);


        PanelWithButtons panelWithButtons = new PanelWithButtons() {

            {
                initPanel();
            }

            @Nullable
            @Override
            protected String getLabelText() {
                return "Servers";
            }

            @Override
            protected JButton[] createButtons() {
                return new JButton[]{};
            }

            @Override
            protected JComponent createMainComponent() {
                table = new JBTable(tableModel);
                table.getEmptyText().setText("No server configuration set");
                table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

                TableColumn autoConnectColumn = table.getColumnModel().getColumn(2);
                int width = table.getFontMetrics(table.getFont()).stringWidth(table.getColumnName(2)) + 10;
                autoConnectColumn.setPreferredWidth(width);
                autoConnectColumn.setMaxWidth(width);
                autoConnectColumn.setMinWidth(width);

                return ToolbarDecorator.createDecorator(table)
                        .setAddAction(new AnActionButtonRunnable() {
                            @Override
                            public void run(AnActionButton button) {
                                stopEditing();

                                ServerConfiguration serverConfiguration = ServerConfiguration.byDefault();

                                ConfigurationDialog dialog = new ConfigurationDialog(mainPanel, project, databaseVendorManager, serverConfiguration);
                                dialog.setTitle("Add a NoSql Server");
                                dialog.show();
                                if (!dialog.isOK()) {
                                    return;
                                }

                                configurations.add(serverConfiguration);
                                int index = configurations.size() - 1;
                                tableModel.fireTableRowsInserted(index, index);
                                table.getSelectionModel().setSelectionInterval(index, index);
                                table.scrollRectToVisible(table.getCellRect(index, 0, true));
                            }
                        })
                        .setAddActionName("addServer")
                        .setEditAction(new AnActionButtonRunnable() {
                            @Override
                            public void run(AnActionButton button) {
                                stopEditing();

                                int selectedIndex = table.getSelectedRow();
                                if (selectedIndex < 0 || selectedIndex >= tableModel.getRowCount()) {
                                    return;
                                }
                                ServerConfiguration sourceConfiguration = configurations.get(selectedIndex);
                                ServerConfiguration copiedConfiguration = sourceConfiguration.clone();


                                ConfigurationDialog dialog = new ConfigurationDialog(mainPanel, project , databaseVendorManager, copiedConfiguration);
                                dialog.setTitle("Edit a NoSql Server");
                                dialog.show();
                                if (!dialog.isOK()) {
                                    return;
                                }

                                configurations.set(selectedIndex, copiedConfiguration);
                                tableModel.fireTableRowsUpdated(selectedIndex, selectedIndex);
                                table.getSelectionModel().setSelectionInterval(selectedIndex, selectedIndex);
                            }
                        })
                        .setEditActionName("editServer")
                        .setRemoveAction(new AnActionButtonRunnable() {
                            @Override
                            public void run(AnActionButton button) {
                                stopEditing();

                                int selectedIndex = table.getSelectedRow();
                                if (selectedIndex < 0 || selectedIndex >= tableModel.getRowCount()) {
                                    return;
                                }
                                TableUtil.removeSelectedItems(table);
                            }
                        })
                        .setRemoveActionName("removeServer")
                        .disableUpDownActions().createPanel();
            }
        };

        mainPanel.add(panelWithButtons, BorderLayout.CENTER);

        return mainPanel;
    }

    public boolean isModified() {
        return areConfigurationsModified() || isRedisShellPathModified();
    }

    @Override
    public void apply() throws ConfigurationException {
        stopEditing();
        if (areConfigurationsModified()) {
            configuration.setServerConfigurations(configurations);
        }

        if (isMongoShellPathModified()) {
            configuration.setShellPath(DatabaseVendor.MONGO, mongoShellPanel.getShellPath());
        }

        if (isRedisShellPathModified()) {
            configuration.setShellPath(DatabaseVendor.REDIS, redisShellPanel.getShellPath());
        }

        NoSqlWindowManager.getInstance(project).apply();
    }

    private boolean isMongoShellPathModified() {
        return mongoShellPanel.isShellPathModified(NoSqlConfiguration.getInstance(project).getShellPath(DatabaseVendor.MONGO));
    }

    private boolean isRedisShellPathModified() {
        return redisShellPanel.isShellPathModified(NoSqlConfiguration.getInstance(project).getShellPath(DatabaseVendor.REDIS));
    }

    private boolean areConfigurationsModified() {
        List<ServerConfiguration> existingConfigurations = NoSqlConfiguration.getInstance(project).getServerConfigurations();

        if (configurations.size() != existingConfigurations.size()) {
            return true;
        }

        for (ServerConfiguration existingConfiguration : existingConfigurations) {
            if (!configurations.contains(existingConfiguration)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void reset() {
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        tableModel.removeTableModelListener(table);
        mongoShellPanel.dispose();
        redisShellPanel.dispose();
        table = null;
    }

    private void stopEditing() {
        if (table.isEditing()) {
            TableCellEditor editor = table.getCellEditor();
            if (editor != null) {
                editor.stopCellEditing();
            }
        }
    }


    public static boolean checkShellPath(String shellPath) throws ExecutionException {
        if (isBlank(shellPath)) {
            return false;
        }

        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.setExePath(shellPath);
        commandLine.addParameter("--version");
        CapturingProcessHandler handler = new CapturingProcessHandler(commandLine.createProcess(), CharsetToolkit.getDefaultSystemCharset());
        ProcessOutput result = handler.runProcess(15 * 1000);
        return result.getExitCode() == 0;
    }


    private class ShellPathPanel extends JPanel implements Disposable {

        private LabeledComponent<TextFieldWithBrowseButton> shellPathField;
        private JLabel testPathFeedbackLabel;

        private ShellPathPanel(DatabaseVendor databaseVendor) {
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            add(createLabel(databaseVendor.name));
            shellPathField = createShellPathField(databaseVendor);
            add(shellPathField);
            add(createTestButton(databaseVendor));
            testPathFeedbackLabel = new JLabel();
            add(testPathFeedbackLabel);
        }

        private JLabel createLabel(String databaseVendorName) {
            return new JLabel(String.format("Path to %s CLI:", databaseVendorName));
        }

        private JButton createTestButton(final DatabaseVendor databaseVendorName) {
            JButton testButton = new JButton("Test");
            testButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    testPath(databaseVendorName);
                }
            });
            return testButton;
        }


        private void testPath(DatabaseVendor databaseVendor) {
            try {
                testPathFeedbackLabel.setIcon(null);
                if (checkShellPath(getShellPath())) {
                    testPathFeedbackLabel.setIcon(ServerConfigurationPanel.SUCCESS);
                } else {
                    testPathFeedbackLabel.setIcon(ServerConfigurationPanel.FAIL);
                }
            } catch (ExecutionException e) {
                Messages.showErrorDialog(mainPanel, e.getMessage(), String.format("Error During %s Shell Path Checking...", databaseVendor));
            }
        }

        public String getShellPath() {
            String shellPath = shellPathField.getComponent().getText();
            if (StringUtils.isNotBlank(shellPath)) {
                return shellPath;
            }

            return null;
        }

        public boolean isShellPathModified(String shellPath) {
            return !StringUtils.equals(shellPath, getShellPath());
        }

        private LabeledComponent<TextFieldWithBrowseButton> createShellPathField(DatabaseVendor databaseVendor) {
            LabeledComponent<TextFieldWithBrowseButton> shellPathField = new LabeledComponent<TextFieldWithBrowseButton>();
            TextFieldWithBrowseButton component = new TextFieldWithBrowseButton();
            component.getChildComponent().setName("shellPathField");
            shellPathField.setComponent(component);
            shellPathField.getComponent().addBrowseFolderListener(String.format("%s CLI configuration", databaseVendor.name), "", null,
                    new FileChooserDescriptor(true, false, false, false, false, false));

            shellPathField.getComponent().setText(configuration.getShellPath(databaseVendor));

            return shellPathField;
        }

        @Override
        public void dispose() {
            shellPathField = null;
        }
    }
}