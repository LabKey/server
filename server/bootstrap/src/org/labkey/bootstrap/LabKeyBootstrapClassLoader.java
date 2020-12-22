/*
 * Copyright (c) 2017 LabKey Corporation
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

package org.labkey.bootstrap;

import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.loader.WebappClassLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * User: jeckels
 * Date: Jun 8, 2006
 */
public class LabKeyBootstrapClassLoader extends WebappClassLoader implements ExplodedModuleService
{
    private final SimpleLogger _log = new CommonsLogger(LabKeyBootstrapClassLoader.class);

    /** Modules which have been previously logged as having changed, which would trigger a webapp redeployment in development scenarios */
    private final Set<String> _previouslyLoggedModules = new HashSet<>();
    private final ReentrantLock moduleLoading = new ReentrantLock();

    // IMPORTANT see also ContextListener which duplicates this code, keep them consistent
    // On startup on some platforms, some modules will die if java.awt.headless is not set to false.
    // Only set this if the user hasn't overridden it
    static
    {
        String headless = "java.awt.headless";
        if (System.getProperty(headless) == null)
            System.setProperty(headless, "true");
        // On most installs, catalina.home and catalina.base point to the same directory. However, it's possible
        // to have multiple instances share the Tomcat binaries but have their own ./logs, ./conf, etc directories
        // Thus, we want to use catalina.base for our place to find log files. http://www.jguru.com/faq/view.jsp?EID=1121565
        PipelineBootstrapConfig.ensureLogHomeSet(System.getProperty("catalina.base") + "/logs");

        // Suppress overly verbose logging from Tomcat about WebSocket connections not closing in the ideal pattern
        // https://bz.apache.org/bugzilla/show_bug.cgi?id=59062
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("org.apache.tomcat.websocket.server.WsRemoteEndpointImplServer");
        logger.setLevel(Level.WARNING);
    }

    private ModuleExtractor _moduleExtractor;

    public LabKeyBootstrapClassLoader()
    {
        super();
    }

    public LabKeyBootstrapClassLoader(ClassLoader parent)
    {
        super(parent);
    }

    @Override
    protected void clearReferences()
    {
    }

    @Override
    public void setResources(WebResourceRoot resources)
    {
        super.setResources(resources);

        File webappDir = new File(resources.getContext().getDocBase());
        extract(webappDir);
    }

    private void extract(File webappDir)
    {
        try
        {
            _moduleExtractor = new ModuleExtractor(webappDir, new CommonsLogger(ModuleExtractor.class));
            var explodedModules = _moduleExtractor.extractModules();
            for(var exploded : explodedModules)
            {
                for(URL jarFileUrl : exploded.getJarFileUrls())
                {
                    addURL(jarFileUrl);
                }
            }
        }
        catch(MalformedURLException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean modified()
    {
        boolean modified = false;
        int previousCount = _previouslyLoggedModules.size();

        if (super.modified())
        {
            if (!_previouslyLoggedModules.contains(null))
            {
                _log.info("Standard Tomcat modification check indicates webapp restart is required. Likely an updated JAR file in WEB-INF/lib.");
                _previouslyLoggedModules.add(null);
            }
            modified = true;
        }

        boolean lockAcquired = false;
        try { lockAcquired=moduleLoading.tryLock(0, TimeUnit.MILLISECONDS); } catch (InterruptedException x) { /* pass */}
        if (lockAcquired)
        {
            try
            {
                modified |= _moduleExtractor.areModulesModified(_previouslyLoggedModules);
            }
            finally
            {
                moduleLoading.unlock();
            }
        }

        // On production servers, don't automatically redeploy the web app, which causes Tomcat to leak memory
        if (Boolean.getBoolean("devmode") && modified)
        {
            _previouslyLoggedModules.clear();
            return true;
        }
        else if (modified && _previouslyLoggedModules.size() > previousCount)
        {
            _log.info("Not redeploying webapp, since server is not running in development mode.");
        }
        return false;
    }



    /* ExplodedModuleService interface */

    /*
     * These methods are accessed via reflection from within the main webapp.
     *
     * CONSIDER: use getExplodedModuleService() method instead of directly implementing ExplodedModuleService
     */

    @Override
    public List<File> getExplodedModuleDirectories()
    {
        return _moduleExtractor.getExplodedModuleDirectories();
    }

    @Override
    public List<Map.Entry<File,File>> getExplodedModules()
    {
        return _moduleExtractor.getExplodedModuleDirectoryAndSource();
    }


    /**
     * updatedArchive should not be deployed already, it should be in a temp directory somewhere.
     * After passing this check, existingArchive should be moved/deleted and updatedArchived
     * should be moved into the same directory that existingArchive was in.
     *
     * NOTE: this doesn't guarantee that the webapp won't reload.  The caller has to inspect the archive
     * to ensure that.
     *
     * @param updatedArchive
     * @param existingArchive
     * @return
     */
    public void validateReplaceArchive(File explodedModuleDirectory, File updatedArchive, File existingArchive) throws IOException
    {
        /* check that default deploy directory is the same */
        if (!updatedArchive.isFile())
            throw new FileNotFoundException(updatedArchive.getPath());
        if (!existingArchive.isFile())
            throw new FileNotFoundException(existingArchive.getPath());

        // Since this is replace, we expect existing archive to be extracted already
        if (!_moduleExtractor.hasExplodedArchive(existingArchive))
            throw new IllegalStateException(existingArchive.getAbsolutePath() + " it not an existing archive");

        ModuleArchive existingModuleArchive = new ModuleArchive(existingArchive, _log);
        ModuleArchive updatedModuleArchive = new ModuleArchive(updatedArchive, _log);
        if (!existingModuleArchive.getModuleName().equals(updatedModuleArchive.getModuleName()))
            throw new IllegalArgumentException("Module name doesn't match, expected " + existingModuleArchive.getModuleName());

        File existingTarget = existingModuleArchive.getDefaultExplodedLocation();
        File updatedTarget = updatedModuleArchive.getDefaultExplodedLocation();

        if (!updatedTarget.getName().equals(existingTarget.getName()))
            throw new IllegalArgumentException("Target directories for new and existing archive don't match");
        if (!explodedModuleDirectory.equals(existingTarget))
            throw new IllegalArgumentException("Module archive and exploded directory don't match");
    }

    @Override
    public Map.Entry<File,File> updateModule(File explodedModuleDirectory, File updateArchive, File existingArchive, File mvExistingArchive, boolean dryRun) throws IOException
    {
        File updateArchiveNewHome = new File(existingArchive.getParent(), updateArchive.getName());

        validateReplaceArchive(explodedModuleDirectory, updateArchive, existingArchive);

        // test permissions
        if (mvExistingArchive.exists())
            throw new IllegalArgumentException("file already exists: " + mvExistingArchive.getPath());
        if (!mvExistingArchive.getParentFile().canWrite())
            throw new IllegalArgumentException("can not write file: " + mvExistingArchive.getPath());
        // TODO test updateArchiveNewHome !exists() unless == existingArchive
        if (!updateArchiveNewHome.getParentFile().canWrite())
            throw new IllegalArgumentException("can not write file: " + updateArchiveNewHome.getPath());

        if (dryRun)
            return null;

        List<Callable<Boolean>> undoList = new ArrayList<>();

        // OK we got this far, let's give it a go
        try
        {
            moduleLoading.lock();
            Files.move(existingArchive.toPath(), mvExistingArchive.toPath());
            undoList.add(() -> {
                Files.copy(mvExistingArchive.toPath(), existingArchive.toPath());
                // update timestamp of previous/existing archive to let the usual LabKeyBootstrapClassLoader.modified() do its thing, to help us UNDO
                Files.setLastModifiedTime(existingArchive.toPath(), FileTime.fromMillis(System.currentTimeMillis()));
                return true;
            });

            Files.copy(updateArchive.toPath(), updateArchiveNewHome.toPath());
            undoList.add(() -> {
                Files.delete(updateArchiveNewHome.toPath());
                return true;
            });

            var ret = _moduleExtractor.extractUpdatedModuleArchive(updateArchiveNewHome, existingArchive);

            undoList.clear();
            return ret;
        }
        catch (Throwable t)
        {
            // best attempt to undo
            // NOTE: the loop is last to first like an undo stack
            for (int i=undoList.size()-1 ; i>= 0 ; i--)
            {
                try
                {
                    undoList.get(i).call();
                }
                catch (Exception x)
                {
                    _log.error("Exception occurred while trying to undo failed call to updateModule()", x);
                }
            }
            if (t instanceof IOException)
                throw (IOException)t;
            if (t instanceof RuntimeException)
                throw (RuntimeException)t;
            throw new RuntimeException(t);
        }
        finally
        {
            moduleLoading.unlock();
        }
    }


    public void validateCreateArchive(File newArchive, File target) throws IOException
    {
        if (!newArchive.isFile())
            throw new FileNotFoundException(newArchive.getPath());
        if (target.exists())
            throw new IllegalArgumentException("File already exists: " + target.getPath());

        ModuleArchive newModuleArchive = new ModuleArchive(newArchive, _log);
        String moduleName = newModuleArchive.getModuleName();
        if (null==moduleName || moduleName.isBlank())
            throw new IllegalArgumentException("Module name not found in archive");

        if (newModuleArchive.getDefaultExplodedLocation().exists())
            throw new IllegalArgumentException("Directory already exists: " + newModuleArchive.getDefaultExplodedLocation().getPath());
    }


    @Override
    public Map.Entry<File, File> newModule(File newArchive, File target) throws IOException
    {
        validateCreateArchive(newArchive, target);

        if (!target.getParentFile().canWrite())
            throw new IllegalArgumentException("can not write file: " + target.getPath());

        List<Callable<Boolean>> undoList = new ArrayList<>();

        try
        {
            moduleLoading.lock();
            Files.move(newArchive.toPath(), target.toPath());
            undoList.add(() -> {
                Files.deleteIfExists(target.toPath());
                return true;
            });

            var ret = _moduleExtractor.extractNewModuleArchive(target);

            undoList.clear();
            return ret;
        }
        catch (Throwable t)
        {
            // best attempt to undo
            // NOTE: the loop is last to first like an undo stack
            for (int i=undoList.size()-1 ; i>= 0 ; i--)
            {
                try
                {
                    undoList.get(i).call();
                }
                catch (Exception x)
                {
                    _log.error("Exception occurred while trying to undo failed call to updateModule()", x);
                }
            }
            if (t instanceof IOException)
                throw (IOException)t;
            if (t instanceof RuntimeException)
                throw (RuntimeException)t;
            throw new RuntimeException(t);
        }
        finally
        {
            moduleLoading.unlock();
        }
    }

    @Override
    public File getExternalModulesDirectory()
    {
        return _moduleExtractor._moduleDirectories.getExternalModulesDirectory();
    }

    @Override
    public File getDeletedModulesDirectory()
    {
        File external = getExternalModulesDirectory();
        if (null == external)
            return null;
        File deleted = new File(external, ".deleted");
        if (deleted.isDirectory())
        {
            return deleted;
        }
        // best attempt
        if (deleted.mkdir())
        {
            try
            {
                Files.setAttribute(deleted.toPath(), "dos:hidden", Boolean.TRUE);
            }
            catch (UnsupportedOperationException x)
            {
                /* pass */
            }
            catch (IOException x)
            {
                _log.info("Could not set hidden attribute on directory: " + deleted.getPath());
            }
        }

        return deleted;
    }
}
