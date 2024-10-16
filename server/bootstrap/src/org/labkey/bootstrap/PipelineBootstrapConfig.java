/*
 * Copyright (c) 2008-2018 LabKey Corporation
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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.StringTokenizer;

/*
* User: jeckels
* Date: Jun 26, 2008
*/
public class PipelineBootstrapConfig
{
    public static final String CONFIG_DIR = "configdir";
    public static final String WEBAPP_DIR = "webappdir";
    public static final String PIPELINE_LIB_DIR = "pipelinelibdir";
    public static final String LOG_DIR = "logdir";

    private final static String LOG_HOME_PROPERTY_NAME = "labkey.log.home";

    private File _webappDir;
    private final File _logDir;
    private final List<File> _pipelineLibDirs = new ArrayList<>();
    private File _configDir;
    private final String[] _args;
    private URLClassLoader _classLoader;
    private List<File> _moduleSpringContextFiles;
    private List<File> _customSpringConfigFiles;
    private List<File> _moduleFiles;
    private final boolean _includeWEBINFClasses;

    public PipelineBootstrapConfig(String[] rawArgs, boolean includeWEBINFClasses) throws ConfigException
    {
        _includeWEBINFClasses = includeWEBINFClasses;

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

        if (args.hasOption(LOG_DIR))
        {
            _logDir = new File(args.getOption(LOG_DIR));
        }
        else
        {
            _logDir = new File(_webappDir.getParentFile(), "logs");
        }

        if (args.hasOption(PIPELINE_LIB_DIR))
        {
            StringTokenizer st = new StringTokenizer(args.getOption(PIPELINE_LIB_DIR), File.pathSeparator);
            while (st.hasMoreTokens())
            {
                _pipelineLibDirs.add(new File(st.nextToken()).getAbsoluteFile());
            }
        }
        else
        {
            // Check relative to the working directory
            File libDir = new File("pipeline-lib").getAbsoluteFile();
            if (!libDir.exists())
            {
                // Check relative to the working directory
                libDir = new File("pipelinelib").getAbsoluteFile();
            }
            if (!libDir.exists())
            {
                // Check relative to the working directory
                libDir = new File("pipelineLib").getAbsoluteFile();
            }
            if (!libDir.exists())
            {
                // Check relative to the webapp directory
                libDir = new File(_webappDir.getParentFile(), "pipelinelib").getAbsoluteFile();
            }
            if (!libDir.exists())
            {
                // Check relative to the webapp directory
                libDir = new File(_webappDir.getParentFile(), "pipelineLib").getAbsoluteFile();
            }
            if (!libDir.exists())
            {
                // Check relative to the webapp directory
                libDir = new File(_webappDir.getParentFile(), "pipeline-lib").getAbsoluteFile();
            }
            if (libDir.exists())
            {
                _pipelineLibDirs.add(libDir);
            }
        }
        for (File pipelineLibDir : _pipelineLibDirs)
        {
            if (!pipelineLibDir.exists())
            {
                throw new ConfigException("Could not find pipeline lib directory at " + pipelineLibDir.getAbsolutePath());
            }
        }

        if (args.hasOption(CONFIG_DIR))
        {
            _configDir = new File(args.getOption(CONFIG_DIR));
            if (!_configDir.isDirectory())
            {
                throw new ConfigException("Could not find configuration directory at " + _configDir.getAbsolutePath());
            }
        }

        _args = args.getParameters().toArray(new String[0]);
    }

    public String[] getProgramArgs()
    {
        return _args;
    }

    public File getWebappDir()
    {
        return _webappDir;
    }

    public File getConfigDir()
    {
        return _configDir;
    }

    public File getLogDir()
    {
        return _logDir;
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
            _moduleFiles = new ArrayList<>(extractor.getExplodedModuleDirectories());
            _moduleSpringContextFiles = new ArrayList<>();

            List<URL> jarUrls = new ArrayList<>();
            try
            {
                if (_includeWEBINFClasses)
                {
                    File webInfDir = new File(_webappDir, "WEB-INF");
                    File classesDir = new File(webInfDir, "classes");
                    jarUrls.add(classesDir.toURI().toURL());
                }

                for (File libDir : _pipelineLibDirs)
                {
                    for (File file : libDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar")))
                    {
                        jarUrls.add(file.toURI().toURL());
                    }
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

            _customSpringConfigFiles = new ArrayList<>();
            if (_configDir != null)
            {
                addConfigFiles(_configDir);
            }

            _classLoader = new URLClassLoader(jarUrls.toArray(new URL[0]), ClusterBootstrap.class.getClassLoader());
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
        File[] subDirs = configDir.listFiles(File::isDirectory);
        for (File subDir : subDirs)
        {
            addConfigFiles(subDir);
        }

        File[] xmlFiles = configDir.listFiles(pathname -> pathname.getName().toLowerCase().endsWith(".xml"));
        _customSpringConfigFiles.addAll(Arrays.asList(xmlFiles));
    }

    public List<File> getModuleFiles()
    {
        return _moduleFiles;
    }

    public static String ensureLogHomeSet(String location)
    {
        if (System.getProperty(LOG_HOME_PROPERTY_NAME) == null)
            System.setProperty(LOG_HOME_PROPERTY_NAME, location);
        return System.getProperty(LOG_HOME_PROPERTY_NAME);
    }
}