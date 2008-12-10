/*
 * Copyright (c) 2006-2008 LabKey Corporation
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

import org.apache.catalina.loader.WebappClassLoader;

import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import java.io.File;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.List;
import java.util.Collection;

/**
 * User: jeckels
 * Date: Jun 8, 2006
 */
public class LabkeyServerBootstrapClassLoader extends WebappClassLoader
{
    private ModuleExtractor _moduleExtractor;

    public LabkeyServerBootstrapClassLoader()
    {
        super();
    }

    public LabkeyServerBootstrapClassLoader(ClassLoader parent)
    {
        super(parent);
    }

    // This is required to fix a race condition caused by Beehive loading Global.java via reflection.  On the first request after startup,
    // in dev mode, the mothership upgrade thread sends a second request to the server.  If loadClass() is not synchronized we are likely
    // to see java.lang.IllegalArgumentException: org.labkey.core.global in java.lang.ClassLoader.definePackage(ClassLoader.java)
    public synchronized Class loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        return super.loadClass(name, resolve);
    }

    /**
     * This method is accessed via reflection from within the main webapp.
     * Do not rename or remove it without updating its usage in ModuleLoader.
     * @return all the module files that should be used
     */
    public List<File> getExplodedModuleDirectories()
    {
        return _moduleExtractor.getExplodedModuleDirectories();
    }
    
    protected void clearReferences()
    {
    }

    public void setResources(DirContext resources)
    {
        super.setResources(resources);
        try
        {
            File webappDir = new File(resources.getNameInNamespace());

            _moduleExtractor = new ModuleExtractor(webappDir);
            Collection<ExplodedModule> explodedModules = _moduleExtractor.extractModules();
            for(ExplodedModule exploded : explodedModules)
            {
                for(URL jarFileUrl : exploded.getJarFileUrls())
                {
                    addURL(jarFileUrl);
                }
            }
        }
        catch(NamingException e)
        {
            throw new RuntimeException(e);
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException(e);
        }
    }

    public boolean modified()
    {
        return super.modified() || _moduleExtractor.areModulesModified();
    }
}
