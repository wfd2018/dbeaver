/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2022 DBeaver Corp and others
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

package org.jkiss.dbeaver.core;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.PlatformUI;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPExternalFileManager;
import org.jkiss.dbeaver.model.app.*;
import org.jkiss.dbeaver.model.impl.app.DefaultCertificateStorage;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.qm.QMController;
import org.jkiss.dbeaver.model.qm.QMUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.registry.BaseApplicationImpl;
import org.jkiss.dbeaver.registry.BasePlatformImpl;
import org.jkiss.dbeaver.registry.BaseWorkspaceImpl;
import org.jkiss.dbeaver.registry.DataSourceProviderRegistry;
import org.jkiss.dbeaver.runtime.SecurityProviderUtils;
import org.jkiss.dbeaver.runtime.qm.QMControllerImpl;
import org.jkiss.dbeaver.runtime.qm.QMLogFileWriter;
import org.jkiss.dbeaver.ui.resources.DefaultResourceHandlerImpl;
import org.jkiss.dbeaver.utils.ContentUtils;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.SystemVariablesResolver;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.StandardConstants;
import org.osgi.framework.Bundle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * DesktopPlatform
 */
public class DesktopPlatform extends BasePlatformImpl implements DBPPlatformEclipse {

    // The plug-in ID
    public static final String PLUGIN_ID = "org.jkiss.dbeaver.core"; //$NON-NLS-1$

    private static final String TEMP_PROJECT_NAME = ".dbeaver-temp"; //$NON-NLS-1$

    private static final Log log = Log.getLog(DesktopPlatform.class);

    static DesktopPlatform instance;

    @NotNull
    private static volatile boolean isClosing = false;

    private File tempFolder;
    private BaseWorkspaceImpl workspace;
    private QMControllerImpl queryManager;
    private QMLogFileWriter qmLogWriter;
    private DBACertificateStorage certificateStorage;

    private static boolean disposed = false;

    public static DesktopPlatform getInstance() {
        if (instance == null) {
            synchronized (DesktopPlatform.class) {
                if (disposed) {
                    throw new IllegalStateException("DBeaver core already disposed");
                }
                if (instance == null) {
                    // Initialize DBeaver Core
                    DesktopPlatform.createInstance();
                }
            }
        }
        return instance;
    }

    private static DesktopPlatform createInstance() {
        log.debug("Initializing " + GeneralUtils.getProductTitle());
        if (Platform.getProduct() != null) {
            Bundle definingBundle = Platform.getProduct().getDefiningBundle();
            if (definingBundle != null) {
                log.debug("Host plugin: " + definingBundle.getSymbolicName() + " " + definingBundle.getVersion());
            } else {
                log.debug("No product bundle found");
            }
        }

        try {
            instance = new DesktopPlatform();
            instance.initialize();
            return instance;
        } catch (Throwable e) {
            log.error("Error initializing desktop platform", e);
            throw new IllegalStateException("Error initializing desktop platform", e);
        }
    }

    public static String getCorePluginID() {
        return DBeaverActivator.getInstance().getBundle().getSymbolicName();
    }

    public static boolean isStandalone() {
        return BaseApplicationImpl.getInstance().isStandalone();
    }

    public static boolean isClosing() {
        if (isClosing) {
            return true;
        }
        if (!PlatformUI.isWorkbenchRunning()) {
            return false;
        }
        return false;
    }

    static void setClosing(boolean closing) {
        isClosing = closing;
    }

    public static DBPPreferenceStore getGlobalPreferenceStore() {
        return DBeaverActivator.getInstance().getPreferences();
    }

    private DesktopPlatform() {
    }

    protected void initialize() {
        long startTime = System.currentTimeMillis();
        log.debug("Initialize Core...");

        if (getPreferenceStore().getBoolean(DBeaverPreferences.SECURITY_USE_BOUNCY_CASTLE)) {
            // Register BC security provider
            SecurityProviderUtils.registerSecurityProvider();
        }

        this.certificateStorage = new DefaultCertificateStorage(
            new File(DBeaverActivator.getInstance().getStateLocation().toFile(), "security"));

        // Create workspace
        this.workspace = (BaseWorkspaceImpl) getApplication().createWorkspace(this, ResourcesPlugin.getWorkspace());
        // Init workspace in UI because it may need some UI interactions to initialize
        this.workspace.initializeProjects();

        QMUtils.initApplication(this);
        this.queryManager = new QMControllerImpl();

        this.qmLogWriter = new QMLogFileWriter();
        this.queryManager.registerMetaListener(qmLogWriter);

        super.initialize();

        log.debug("Core initialized (" + (System.currentTimeMillis() - startTime) + "ms)");
    }

    public synchronized void dispose() {
        long startTime = System.currentTimeMillis();
        log.debug("Shutdown Core...");

        DesktopPlatform.setClosing(true);
        DBPApplication application = getApplication();
        if (application instanceof DBPApplicationController) {
            // Shutdown in headless mode
            ((DBPApplicationController) application).setHeadlessMode(true);
        }

        super.dispose();

        workspace.dispose();

        if (this.qmLogWriter != null) {
            this.queryManager.unregisterMetaListener(qmLogWriter);
            this.qmLogWriter.dispose();
            this.qmLogWriter = null;
        }
        if (this.queryManager != null) {
            this.queryManager.dispose();
            //queryManager = null;
        }
        DataSourceProviderRegistry.getInstance().dispose();

        if (isStandalone() && workspace != null && !application.isExclusiveMode()) {
            try {
                workspace.save(new VoidProgressMonitor());
            } catch (DBException ex) {
                log.error("Can't save workspace", ex); //$NON-NLS-1$
            }
        }

        // Remove temp folder
        if (tempFolder != null) {

            if (!ContentUtils.deleteFileRecursive(tempFolder)) {
                log.warn("Can't delete temp folder '" + tempFolder.getAbsolutePath() + "'");
            }
            tempFolder = null;
        }

        DesktopPlatform.instance = null;
        DesktopPlatform.disposed = true;
        System.gc();
        log.debug("Shutdown completed in " + (System.currentTimeMillis() - startTime) + "ms");
    }

    @NotNull
    @Override
    public DBPWorkspaceEclipse getWorkspace() {
        return workspace;
    }

    @NotNull
    @Override
    public DBPResourceHandler getDefaultResourceHandler() {
        return DefaultResourceHandlerImpl.INSTANCE;
    }

    @NotNull
    @Override
    public DBPApplication getApplication() {
        return BaseApplicationImpl.getInstance();
    }

/*
    private void changeRuntimeLocale(@NotNull DBPPlatformLanguage runtimeLocale) {
        // Check locale
        ILocaleChangeService localeService = PlatformUI.getWorkbench().getService(ILocaleChangeService.class);
        if (localeService != null) {
            localeService.changeApplicationLocale(runtimeLocale.getCode());
        } else {
            log.warn("Can't resolve locale change service");
        }

        Locale locale = Locale.forLanguageTag(runtimeLocale.getCode());
        if (locale != null) {
            Locale.setDefault(locale);
        }
    }
*/

    @NotNull
    public QMController getQueryManager() {
        return queryManager;
    }

    @NotNull
    @Override
    public DBPPreferenceStore getPreferenceStore() {
        return DBeaverActivator.getInstance().getPreferences();
    }

    @NotNull
    @Override
    public DBACertificateStorage getCertificateStorage() {
        return certificateStorage;
    }

    @NotNull
    @Override
    public DBASecureStorage getSecureStorage() {
        return getApplication().getSecureStorage();
    }

    @NotNull
    @Override
    public DBPExternalFileManager getExternalFileManager() {
        return workspace;
    }

    @NotNull
    public File getTempFolder(DBRProgressMonitor monitor, String name) {
        if (tempFolder == null) {
            // Make temp folder
            monitor.subTask("Create temp folder");
            try {
                String tempFolderPath = System.getProperty("dbeaver.io.tmpdir");
                if (!CommonUtils.isEmpty(tempFolderPath)) {
                    tempFolderPath = GeneralUtils.replaceVariables(tempFolderPath, new SystemVariablesResolver());

                    File dbTempFolder = new File(tempFolderPath);
                    if (!dbTempFolder.mkdirs()) {
                        throw new IOException("Can't create temp directory '" + dbTempFolder.getAbsolutePath() + "'");
                    }
                } else {
                    tempFolderPath = System.getProperty(StandardConstants.ENV_TMP_DIR);
                }
                final java.nio.file.Path tempDirectory = Files.createTempDirectory(
                    Paths.get(tempFolderPath),
                    TEMP_PROJECT_NAME);
                tempFolder = tempDirectory.toFile();
            } catch (IOException e) {
                final String sysTempFolder = System.getProperty(StandardConstants.ENV_TMP_DIR);
                if (!CommonUtils.isEmpty(sysTempFolder)) {
                    tempFolder = new File(sysTempFolder, TEMP_PROJECT_NAME);
                    if (!tempFolder.mkdirs()) {
                        final String sysUserFolder = System.getProperty(StandardConstants.ENV_USER_HOME);
                        if (!CommonUtils.isEmpty(sysUserFolder)) {
                            tempFolder = new File(sysUserFolder, TEMP_PROJECT_NAME);
                            if (!tempFolder.mkdirs()) {
                                tempFolder = new File(TEMP_PROJECT_NAME);
                            }
                        }

                    }
                }
            }
        }
        if (!tempFolder.exists() && !tempFolder.mkdirs()) {
            log.error("Can't create temp directory " + tempFolder.getAbsolutePath());
        }
        return tempFolder;
    }

    @NotNull
    @Override
    public File getConfigurationFile(String fileName) {
        return DBeaverActivator.getConfigurationFile(fileName);
    }

    @Override
    public boolean isShuttingDown() {
        return isClosing();
    }

}
