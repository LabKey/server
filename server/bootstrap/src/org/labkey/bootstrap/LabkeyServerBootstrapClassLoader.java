package org.labkey.bootstrap;

import org.apache.catalina.loader.WebappClassLoader;

import javax.naming.directory.DirContext;
import javax.naming.NamingException;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.io.*;

/**
 * User: jeckels
 * Date: Jun 8, 2006
 */
public class LabkeyServerBootstrapClassLoader extends WebappClassLoader
{
    private Set<File> _moduleFiles;
    private List<ModuleFileWatcher> _watchers = new ArrayList<ModuleFileWatcher>();

    public static final String MODULE_ARCHIVE_EXTENSION = ".module";

    private File _modulesDir;

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
     */
    public Set<File> getModuleFiles()
    {
        return _moduleFiles;
    }

    public Set<File> examineModuleFiles()
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
                    return name.toLowerCase().endsWith(MODULE_ARCHIVE_EXTENSION);
                }
            });
        Arrays.sort(moduleArchives);
        Set<File> result = new LinkedHashSet<File>();
        for (File f : moduleArchives)
        {
            result.add(f);
        }

        return result;
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
            if (moduleProperty != null)
            {
                _modulesDir = new File(moduleProperty);
            }
            else
            {
                File parentDir = webappDir.getParentFile();
                _modulesDir = new File(parentDir, "modules");
            }

            _moduleFiles = examineModuleFiles();

            for (File moduleArchive : _moduleFiles)
            {
                File moduleLibDir = new File(_modulesDir, "moduleLibDir");
                ModuleFileWatcher moduleFileWatcher = new ModuleFileWatcher(moduleArchive, webappDir);

                JarFile f = null;
                try
                {
                    f = new JarFile(moduleArchive);
                    String moduleName = moduleArchive.getName().substring(0, moduleArchive.getName().length() - MODULE_ARCHIVE_EXTENSION.length());
                    File targetDir = new File(moduleLibDir, moduleName);

                    for (Enumeration<JarEntry> entries = f.entries(); entries.hasMoreElements(); )
                    {
                        JarEntry entry = entries.nextElement();
                        if (entry.getName().toLowerCase().startsWith("meta-inf/lib") && !entry.isDirectory())
                        {
                            File extractedFile = extractEntry(entry, f, targetDir);
                            moduleFileWatcher.addLibraryJar(extractedFile, extractedFile.lastModified());
                            addURL(extractedFile.toURI().toURL());
                        }
                    }
                }
                finally
                {
                    if (f != null) { try { f.close(); } catch (IOException e) {}}
                }

                _watchers.add(moduleFileWatcher);
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

    protected static File extractEntry(JarEntry entry, JarFile moduleArchive, File targetDir) throws IOException
    {
        targetDir.mkdirs();

        File destFile = new File(targetDir, entry.getName().substring(entry.getName().lastIndexOf('/') + 1));

        if (!destFile.exists() ||
            entry.getTime() == -1 ||
            entry.getTime() < (destFile.lastModified() - 2000) ||
            entry.getTime() > (destFile.lastModified() + 2000) ||
            entry.getSize() == -1 ||
            entry.getSize() != destFile.length())
        {
            BufferedInputStream bIn = null;
            BufferedOutputStream bOut = null;
            try
            {
                bIn = new BufferedInputStream(moduleArchive.getInputStream(entry));
                bOut = new BufferedOutputStream(new FileOutputStream(destFile));
                byte[] b = new byte[8192];
                int i;
                while ((i = bIn.read(b)) != -1)
                {
                    bOut.write(b, 0, i);
                }
            }
            finally
            {
                if (bIn != null) { try { bIn.close(); } catch (IOException e) {}}
                if (bOut != null) { try { bOut.close(); } catch (IOException e) {}}
            }
            if (entry.getTime() != -1)
            {
                destFile.setLastModified(entry.getTime());
            }
        }
        return destFile;
    }


    public boolean modified()
    {
        boolean modified = super.modified();
        if (modified)
        {
            return true;
        }

        Set<File> oldModuleFiles = _moduleFiles;
        Set<File> newModuleFiles = examineModuleFiles();
        if (!oldModuleFiles.equals(newModuleFiles))
        {
            return true;
        }

        for (ModuleFileWatcher moduleFileWatcher : _watchers)
        {
            if (moduleFileWatcher.modified())
            {
                return true;
            }
        }
        return false;
    }

}
