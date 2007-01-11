package org.labkey.bootstrap;

import org.apache.catalina.loader.WebappClassLoader;

import javax.naming.directory.DirContext;
import javax.naming.NamingException;
import java.util.*;
import java.io.*;

/**
 * User: jeckels
 * Date: Jun 8, 2006
 */
public class LabkeyServerBootstrapClassLoader extends WebappClassLoader
{
    private Map<File, ModuleClassLoader> _classLoaderMap = new HashMap<File, ModuleClassLoader>();

    private final Map<String, ClassCacheEntry> _classCache = Collections.synchronizedMap(new HashMap<String, ClassCacheEntry>());
    private final Map<String, ClassCacheEntry> _moduleClassCache = Collections.synchronizedMap(new HashMap<String, ClassCacheEntry>());
    private File _modulesDir;

    public LabkeyServerBootstrapClassLoader()
    {
        super();
    }

    public LabkeyServerBootstrapClassLoader(ClassLoader parent)
    {
        super(parent);
    }

    private File[] getModuleFiles()
    {
        if (!_modulesDir.isDirectory())
        {
            try
            {
                throw new IllegalArgumentException("Unable to find modules directory - " + _modulesDir.getCanonicalPath() + ", this can be set with -Dcpas.modulesDir=<modulesDir>");
            }
            catch (IOException e)
            {
                throw new IllegalArgumentException("Unable to find modules directory - " + _modulesDir.getAbsolutePath() + ", this can be set with -Dcpas.modulesDir=<modulesDir>");
            }
        }

        File[] moduleArchives = _modulesDir.listFiles(new FilenameFilter()
            {
                public boolean accept(File dir, String name)
                {
                    return name.toLowerCase().endsWith(ModuleClassLoader.MODULE_ARCHIVE_EXTENSION);
                }
            });
        Arrays.sort(moduleArchives);

        return moduleArchives;
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
            String moduleProperty = System.getProperty("cpas.modulesDir");
            if (moduleProperty != null)
            {
                _modulesDir = new File(moduleProperty);
            }
            else
            {
                File parentDir = webappDir.getParentFile();
                _modulesDir = new File(parentDir, "modules");
            }

            File[] moduleArchives = getModuleFiles();
            ModuleParentClassLoader moduleParent = new ModuleParentClassLoader(this);

            for (File moduleArchive : moduleArchives)
            {
                ModuleClassLoader moduleClassLoader = new ModuleClassLoader(moduleParent, moduleArchive, moduleArchive.getParentFile(), this, webappDir);
                _classLoaderMap.put(moduleArchive, moduleClassLoader);
            }
        }
        catch (NamingException e)
        {
            throw new RuntimeException(e);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * This method is accessed via reflection from within the main webapp.
     * Do not rename or remove it without updating its usage in ModuleLoader.
     */

    public Map<File, ModuleClassLoader> getClassLoaders()
    {
        return _classLoaderMap;
    }

    public void closeJARs(boolean force)
    {
        super.closeJARs(force);
        for (ModuleClassLoader loader : _classLoaderMap.values())
        {
            // Todo - close these JARs
        }
    }

    public InputStream getResourceAsStream(String name)
    {
        return getResourceAsStream(name, true);
    }

    // Todo - handle other delegating to module classloaders for other resources?

    public InputStream getResourceAsStream(String name, boolean searchModules)
    {
        InputStream result = super.getResourceAsStream(name);
        if (result == null && searchModules)
        {
            for (ModuleClassLoader moduleLoader : _classLoaderMap.values())
            {
                result = moduleLoader.getResourceAsStream(name);
                if (result != null)
                {
                    break;
                }
            }
        }
        return result;
    }

    public Class loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        return loadClass(name, resolve, true);
    }

    private static class ClassCacheEntry
    {
        private Class _class;

        public ClassCacheEntry(Class c)
        {
            _class = c;
        }

        public Class getCachedClass()
        {
            return _class;
        }
    }

    public Class loadClass(String name, boolean resolve, boolean searchModules) throws ClassNotFoundException
    {
        ClassCacheEntry cacheEntry = _classCache.get(name);
        if (cacheEntry != null)
        {
            if (cacheEntry.getCachedClass() == null)
            {
                if (searchModules)
                {
                    ClassCacheEntry moduleCacheEntry = _moduleClassCache.get(name);
                    if (moduleCacheEntry != null)
                    {
                        if (moduleCacheEntry.getCachedClass() == null)
                        {
                            throw new ClassNotFoundException(name);
                        }
                        else
                        {
                            return moduleCacheEntry.getCachedClass();
                        }
                    }
                }
                else
                {
                    throw new ClassNotFoundException(name);
                }
            }

            if (cacheEntry.getCachedClass() != null)
            {
                return cacheEntry.getCachedClass();
            }
        }

        try
        {
            Class result = super.loadClass(name, resolve);
            _classCache.put(name, new ClassCacheEntry(result));
            return result;
        }
        catch (ClassNotFoundException e)
        {
            _classCache.put(name, new ClassCacheEntry(null));
            if (searchModules && !_classLoaderMap.isEmpty())
            {
                for (ModuleClassLoader moduleLoader : _classLoaderMap.values())
                {
                    try
                    {
                        Class result = moduleLoader.loadClass(name);
                        _moduleClassCache.put(name, new ClassCacheEntry(result));
                        return result;
                    }
                    catch (ClassNotFoundException e2) {}
                }
                _moduleClassCache.put(name, new ClassCacheEntry(null));
            }
            throw e;
        }
    }

    public boolean modified()
    {
        boolean modified = super.modified();
        if (modified)
        {
            return true;
        }

        Set<File> oldModuleFiles = _classLoaderMap.keySet();
        Set<File> newModuleFiles = new HashSet<File>(Arrays.asList(getModuleFiles()));
        if (!oldModuleFiles.equals(newModuleFiles))
        {
            return true;
        }

        for (ModuleClassLoader moduleClassLoader : _classLoaderMap.values())
        {
            if (moduleClassLoader.modified())
            {
                return true;
            }
        }
        return false;
    }

}
