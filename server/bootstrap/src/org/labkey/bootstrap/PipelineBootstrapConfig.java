/*
 * Copyright (c) 2008-2009 LabKey Corporation
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

import java.io.File;
import java.io.IOException;
import java.io.FileFilter;
import java.util.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.MalformedURLException;

/*
* User: jeckels
* Date: Jun 26, 2008
*/
public class PipelineBootstrapConfig
{
    public static final String CONFIG_DIR = "configdir";
    public static final String WEBAPP_DIR = "webappdir";
    public static final String PIPELINE_LIB_DIR = "pipelinelibdir";

    private File _webappDir;
    private File _libDir;
    private File _pipelineLibDir;
    private File _configDir;
    private String[] _args;
    private URLClassLoader _classLoader;
    private List<File> _moduleSpringContextFiles;
    private List<File> _customSpringConfigFiles;
    private List<File> _moduleFiles;

    public PipelineBootstrapConfig(String[] rawArgs) throws IOException, ConfigException
    {
        ArgumentParser args = new ArgumentParser(rawArgs);
        if (args.hasOption(WEBAPP_DIR))
        {
            _webappDir = new File(args.getOption(WEBAPP_DIR)).getAbsoluteFile();
        }
        else
        {
            _webappDir = new File("labkeywebapp").getAbsoluteFile();
            if (!_webappDir.isDirectory())
            {
                _webappDir = new File("webapp");
            }
            if (!_webappDir.isDirectory())
            {
                _webappDir = new File("labkeyWebapp");
            }
        }

        if (!_webappDir.isDirectory())
        {
            throw new ConfigException("Could not find webapp directory at " + _webappDir.getAbsolutePath());
        }

        if (args.hasOption(PIPELINE_LIB_DIR))
        {
            _pipelineLibDir = new File(args.getOption(PIPELINE_LIB_DIR)).getAbsoluteFile();
        }
        else
        {
            // Check relative to the working directory
            _pipelineLibDir = new File("pipeline-lib").getAbsoluteFile();
            if (!_pipelineLibDir.exists())
            {
                // Check relative to the working directory
                _pipelineLibDir = new File("pipelinelib").getAbsoluteFile();
            }
            if (!_pipelineLibDir.exists())
            {
                // Check relative to the working directory
                _pipelineLibDir = new File("pipelineLib").getAbsoluteFile();
            }
            if (!_pipelineLibDir.exists())
            {
                // Check relative to the webapp directory
                _pipelineLibDir = new File(_webappDir.getParentFile(), "pipelinelib").getAbsoluteFile();
            }
            if (!_pipelineLibDir.exists())
            {
                // Check relative to the webapp directory
                _pipelineLibDir = new File(_webappDir.getParentFile(), "pipelineLib").getAbsoluteFile();
            }
            if (!_pipelineLibDir.exists())
            {
                // Check relative to the webapp directory
                _pipelineLibDir = new File(_webappDir.getParentFile(), "pipeline-lib").getAbsoluteFile();
            }
        }
        if (!_pipelineLibDir.exists())
        {
            throw new ConfigException("Could not find pipeline lib directory at " + _pipelineLibDir.getAbsolutePath());
        }

        File webinfDir = new File(_webappDir, "WEB-INF");
        _libDir = new File(webinfDir, "lib");

        if (!_libDir.isDirectory())
        {
            throw new ConfigException("Could not find subdirectory WEB-INF/lib in webapp, expected to be at " + _libDir.getAbsolutePath());
        }

        if (args.hasOption(CONFIG_DIR))
        {
            _configDir = new File(args.getOption(CONFIG_DIR));
            if (!_configDir.isDirectory())
            {
                throw new ConfigException("Could not find configuration directory at " + _configDir.getAbsolutePath());
            }
        }

        _args = args.getParameters().toArray(new String[args.getParameters().size()]);
    }

    public String[] getProgramArgs()
    {
        return _args;
    }

    public File getWebappDir()
    {
        return _webappDir;
    }

    public File getLibDir()
    {
        return _libDir;
    }

    public File getConfigDir()
    {
        return _configDir;
    }

    public ClassLoader getClassLoader()
    {
        init();
        return _classLoader;
    }

    private synchronized void init()
    {
        if (_classLoader == null)
        {
            ModuleExtractor extractor = new ModuleExtractor(getWebappDir(), new StdOutLogger());
            Collection<ExplodedModule> explodedModules = extractor.extractModules();
            _moduleFiles = new ArrayList<File>(extractor.getExplodedModuleDirectories());
            _moduleSpringContextFiles = new ArrayList<File>();

            List<URL> jarUrls = new ArrayList<URL>();

            try
            {
                for (File file : _libDir.listFiles())
                {
                    jarUrls.add(file.toURI().toURL());
                }

                for (File file : _pipelineLibDir.listFiles())
                {
                    jarUrls.add(file.toURI().toURL());
                }

                for(ExplodedModule explodedModule : explodedModules)
                {
                    jarUrls.addAll(Arrays.asList(explodedModule.getJarFileUrls()));
                    _moduleSpringContextFiles.addAll(explodedModule.getSpringConfigFiles());
                }
            }
            catch (MalformedURLException e)
            {
                throw new RuntimeException(e);
            }

            _customSpringConfigFiles = new ArrayList<File>();
            if (_configDir != null)
            {
                addConfigFiles(_configDir);
            }

            _classLoader = new URLClassLoader(jarUrls.toArray(new URL[jarUrls.size()]), ClusterBootstrap.class.getClassLoader());
        }
    }

    public List<File> getModuleSpringConfigFiles()
    {
        return _moduleSpringContextFiles;
    }

    public List<File> getCustomSpringConfigFiles()
    {
        return _customSpringConfigFiles;
    }

    // Traverse the directory structure looking for files that match **/*.xml
    private void addConfigFiles(File configDir)
    {
        File[] subDirs = configDir.listFiles(new FileFilter()
        {
            public boolean accept(File pathname)
            {
                return pathname.isDirectory();
            }
        });
        for (File subDir : subDirs)
        {
            addConfigFiles(subDir);
        }

        File[] xmlFiles = configDir.listFiles(new FileFilter()
        {
            public boolean accept(File pathname)
            {
                return pathname.getName().toLowerCase().endsWith(".xml");
            }
        });
        _customSpringConfigFiles.addAll(Arrays.asList(xmlFiles));
    }

    public List<File> getModuleFiles()
    {
        return _moduleFiles;
    }
}