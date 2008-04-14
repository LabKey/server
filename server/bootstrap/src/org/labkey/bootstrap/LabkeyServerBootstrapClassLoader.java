package org.labkey.bootstrap;

import org.apache.catalina.loader.WebappClassLoader;

import javax.naming.directory.DirContext;
import javax.naming.NamingException;
import java.util.*;
import java.io.*;
import java.net.MalformedURLException;

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
    public Set<File> getModuleFiles()
    {
        return _moduleExtractor.getModuleFiles();
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
            String moduleProperty = System.getProperty("labkey.modulesDir");
            if (moduleProperty == null)
            {
                moduleProperty = System.getProperty("cpas.modulesDir");
            }

            List<File> moduleDirectories = new ArrayList<File>();

            File modulesDir;
            if (moduleProperty != null)
            {
                modulesDir = new File(moduleProperty);
            }
            else
            {
                File parentDir = webappDir.getParentFile();
                modulesDir = new File(parentDir, "modules");
            }

            if (!modulesDir.isDirectory())
            {
                try
                {
                    throw new IllegalArgumentException("Unable to find modules directory - " + modulesDir.getCanonicalPath() + ", this can be set with -Dlabkey.modulesDir=<modulesDir>");
                }
                catch (IOException e)
                {
                    throw new IllegalArgumentException("Unable to find modules directory - " + modulesDir.getAbsolutePath() + ", this can be set with -Dlabkey.modulesDir=<modulesDir>");
                }
            }
            moduleDirectories.add(modulesDir);

            String externalModuleProperty = System.getProperty("labkey.externalModulesDir");
            File externalModulesDir;
            if (externalModuleProperty != null)
            {
                externalModulesDir = new File(externalModuleProperty);
                if (!externalModulesDir.isDirectory())
                {
                    throw new IllegalArgumentException("Could not find external modules directory (-Dlabkey.externalModulesDir) " + externalModulesDir);
                }
            }
            else
            {
                File parentDir = webappDir.getParentFile();
                externalModulesDir = new File(parentDir, "externalModules");
            }
            if (externalModulesDir.isDirectory())
            {
                moduleDirectories.add(externalModulesDir);
            }

            _moduleExtractor = new ModuleExtractor(moduleDirectories);

            List<File> jars = _moduleExtractor.extractModules(webappDir).getJarFiles();
            for (File jar : jars)
            {
                addURL(jar.toURI().toURL());
            }
        }
        catch (NamingException e)
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
        boolean modified = super.modified();
        if (modified)
        {
            return true;
        }

        Set<File> oldModuleFiles = _moduleExtractor.getModuleFiles();
        Set<File> newModuleFiles = _moduleExtractor.examineModuleFiles();
        if (!oldModuleFiles.equals(newModuleFiles))
        {
            return true;
        }

        for (ModuleFileWatcher moduleFileWatcher : _moduleExtractor.getWatchers())
        {
            if (moduleFileWatcher.modified())
            {
                return true;
            }
        }
        return false;
    }

}
