/*
 * Copyright (c) 2008 LabKey Corporation
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.io.*;
import java.util.*;

/**
 * Extracts modules into exploded module directories
 */
public class ModuleExtractor
{
    public final FilenameFilter moduleArchiveFilter = new FilenameFilter(){
        public boolean accept(File dir, String name)
        {
            return name.toLowerCase().endsWith(ModuleArchive.FILE_EXTENSION);
        }
    };

    protected final File _webAppDirectory;
    protected final ModuleDirectories _moduleDirectories;

    private Set<File> _moduleArchiveFiles;
    private Set<ExplodedModule> _explodedModules;

    private static final Log _log = LogFactory.getLog(ModuleExtractor.class);

    public ModuleExtractor(File webAppDirectory)
    {
        _webAppDirectory = webAppDirectory;
        _moduleDirectories = new ModuleDirectories(_webAppDirectory);
    }

    public Collection<ExplodedModule> extractModules()
    {
        _moduleArchiveFiles = new HashSet<File>();

        //explode all module archives
        for(File moduleDir : _moduleDirectories.getAllModuleDirectories())
        {
            for(File moduleArchiveFile : moduleDir.listFiles(moduleArchiveFilter))
            {
                try
                {
                    ModuleArchive moduleArchive = new ModuleArchive(moduleArchiveFile);
                    moduleArchive.extractAll();
                    _moduleArchiveFiles.add(moduleArchiveFile);
                }
                catch(IOException e)
                {
                    _log.error("Unable to extract the module archive " + moduleArchiveFile.getPath() + "!", e);
                }
            }
        }

        //gather all the exploded module directories
        _explodedModules = new HashSet<ExplodedModule>();
        for(File moduleDir : _moduleDirectories.getAllModuleDirectories())
        {
            for(File dir : moduleDir.listFiles())
            {
                if(dir.isDirectory())
                {
                    try
                    {
                        ExplodedModule explodedModule = new ExplodedModule(dir);
                        explodedModule.deployToWebApp(_webAppDirectory);
                        _explodedModules.add(explodedModule);
                    }
                    catch(IOException e)
                    {
                        _log.error("Unable to deploy the resources from the exploded module " + dir.getPath() + " to the web app directory!");
                    }
                }
            }
        }

        _log.info("Module extraction and deployment complete.");

        return _explodedModules;
    }


    public List<File> getExplodedModuleDirectories()
    {
        List<File> dirs = new ArrayList<File>();
        for(ExplodedModule expMod : _explodedModules)
        {
            dirs.add(expMod.getRootDirectory());
        }
        return dirs;
    }

    public boolean areModulesModified()
    {
        if(null == _explodedModules)
            return true;

        //check module archives against exploded modules and check for new modules
        for(File moduleDir : _moduleDirectories.getAllModuleDirectories())
        {
            for(File moduleArchiveFile : moduleDir.listFiles(moduleArchiveFilter))
            {
                //if it's a new module, return true
                if(!_moduleArchiveFiles.contains(moduleArchiveFile))
                    return true;

                //if it's been modified since extraction, re-extract it
                ModuleArchive moduleArchive = new ModuleArchive(moduleArchiveFile);
                if(moduleArchive.isModified())
                {
                    try
                    {
                        moduleArchive.extractAll();
                    }
                    catch(IOException e)
                    {
                        _log.error("Could not re-extract module " + moduleArchive + ". Restarting the web application...");
                        return true;
                    }
                }
            }

            //check for new exploded modules
            for(File dir : moduleDir.listFiles())
            {
                if(dir.isDirectory())
                {
                    ExplodedModule explodedModule = new ExplodedModule(dir);
                    if(!_explodedModules.contains(explodedModule))
                        return true;
                }
            }
        }

        //check existing exploded modules
        for(ExplodedModule explodedModule : _explodedModules)
        {
            if(explodedModule.isModified())
                return true;

            //if not modified, redeploy content to the web app so that
            //new static web content, JSP jars, etc are hot-swapped
            try
            {
                explodedModule.deployToWebApp(_webAppDirectory);
            }
            catch(IOException e)
            {
                _log.error("Could not hot-swap resources from the module " + explodedModule + ". Restarting web application...");
                return true;
            }
        }

        return false;
    }

    /**
     * Extract .module files
     * @param args see usages
     * @throws ConfigException thrown if there is a problem with the configuration
     * @throws IOException thrown if there is a problem extracting the module archives
     */
    public static void main(String... args) throws ConfigException, IOException
    {
        try
        {
            PipelineBootstrapConfig config = new PipelineBootstrapConfig(args);

            ModuleExtractor extractor = new ModuleExtractor(config.getWebappDir());
            extractor.extractModules();
        }
        catch (ConfigException e)
        {
            printUsage(e.getMessage());
        }
    }

    private static void printUsage(String error)
    {
        if (error != null)
        {
            System.err.println(error);
            System.err.println();
        }

        System.err.println("java " + ModuleExtractor.class + " [-" + PipelineBootstrapConfig.MODULES_DIR + "=<MODULE_DIR>] [-" + PipelineBootstrapConfig.WEBAPP_DIR + "=<WEBAPP_DIR>] [-" + PipelineBootstrapConfig.CONFIG_DIR + "=<CONFIG_DIR>]");

        System.exit(1);
    }
}
