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

import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User: jeckels
 * Date: Jun 8, 2006
 */
public class LabKeyBootstrapClassLoader extends WebappClassLoader
{
    private final SimpleLogger _log = new CommonsLogger(LabKeyBootstrapClassLoader.class);

    /** Modules which have been previously logged as having changed, which would trigger a webapp redeployment in development scenarios */
    private final Set<String> _previouslyLoggedModules = new HashSet<>();

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

    /**
     * This method is accessed via reflection from within the main webapp.
     * Do not rename or remove it without updating its usage in ModuleLoader.
     * @return all the module files that should be used
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public List<File> getExplodedModuleDirectories()
    {
        return _moduleExtractor.getExplodedModuleDirectories();
    }
    
    protected void clearReferences()
    {
    }

    // This variant is called when running Tomcat 8
    // @TomcatVersion -- Simplify when Tomcat 8 is required (just call super.setResources())
    @SuppressWarnings("unused")
    public void setResources(WebResourceRoot resources)
    {
        // This is effectively: super.setResources(resources);
        // ...but we use a method handle so this class can be compiled against both Tomcat 7 and Tomcat 8
        invokeSetResourcesOfSuper(resources, WebResourceRoot.class);

        File webappDir = new File(resources.getContext().getDocBase());
        extract(webappDir);
    }

    // This variant is called when running Tomcat 7
    // @TomcatVersion -- Remove when Tomcat 7 is no longer supported
    @SuppressWarnings("unused")
    public void setResources(DirContext resources)
    {
        // This is effectively: super.setResources(resources);
        // ...but we use a method handle so this class can be compiled against both Tomcat 7 and Tomcat 8
        invokeSetResourcesOfSuper(resources, DirContext.class);

        File webappDir;

        try
        {
            webappDir = new File(resources.getNameInNamespace());
        }
        catch(NamingException e)
        {
            throw new RuntimeException(e);
        }

        extract(webappDir);
    }

    // Use method handle to invoke the appropriate LabKeyBootstrapClassLoader.setResources() method so this class can be
    // compiled against both Tomcat 7 and Tomcat 8
    private void invokeSetResourcesOfSuper(Object resources, Class parameterType)
    {
        try
        {
            // Point explicitly at LabKeyBootstrapClassLoader to ensure we can find the setResources() method, even if
            // we're calling from a subclass. See issue 30472
            MethodHandle handle = MethodHandles.lookup().findSpecial(WebappClassLoader.class, "setResources", MethodType.methodType(void.class, parameterType), LabKeyBootstrapClassLoader.class);
            handle.invoke(this, resources);
        }
        catch (Throwable t)
        {
            throw new RuntimeException(t);
        }
    }

    private void extract(File webappDir)
    {
        try
        {
            _moduleExtractor = new ModuleExtractor(webappDir, new CommonsLogger(ModuleExtractor.class));
            Collection<ExplodedModule> explodedModules = _moduleExtractor.extractModules();
            for(ExplodedModule exploded : explodedModules)
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
        modified |= _moduleExtractor.areModulesModified(_previouslyLoggedModules);

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
}
