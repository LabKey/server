package org.labkey.bootstrap;

import java.net.URLClassLoader;
import java.net.URL;
import java.util.Map;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.io.*;

/**
 * User: jeckels
 * Date: Jun 12, 2006
 */
public class ModuleClassLoader extends URLClassLoader
{
    public static final String MODULE_ARCHIVE_EXTENSION = ".module";

    private Map<File, Long> _libraryJarLastModifiedTimes = new HashMap<File, Long>();

    private final ModuleParentClassLoader _parent;
    private final Object _lock;
    private final Map<String, Long> _contentLastModifiedTime;
    private final File _moduleArchive;
    private final File _webappDir;
    private long _moduleLastModifiedTime;

    public ModuleClassLoader(ModuleParentClassLoader parent, File moduleArchive, File workDirectory, Object lock, File webappDir) throws IOException
    {
        super(new URL[0], parent);

        _parent = parent;
        _lock = lock;
        _moduleArchive = moduleArchive;
        _webappDir = webappDir;
        _moduleLastModifiedTime = _moduleArchive.lastModified();

        File moduleLibDir = new File(workDirectory, "moduleLibDir");

        _contentLastModifiedTime = calculateLastModifiedTimes();

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
                    _libraryJarLastModifiedTimes.put(extractedFile, extractedFile.lastModified());
                    addURL(extractedFile.toURI().toURL());
                }
            }
        }
        finally
        {
            if (f != null) { try { f.close(); } catch (IOException e) {}}
        }
    }

    private boolean isIgnoreable(JarEntry entry)
    {
        if (entry.isDirectory())
        {
            return true;
        }
        String name = entry.getName().toLowerCase();
        return name.equals("meta-inf/manifest.mf");
    }

    private boolean isHotDeployable(JarEntry entry)
    {
        if (entry.isDirectory())
        {
            return false;
        }
        String name = entry.getName().toLowerCase();
        return name.toLowerCase().startsWith("meta-inf/jsp");
    }

    private Map<String, Long> calculateLastModifiedTimes()
    {
        JarFile f = null;
        Map<String, Long> result = new HashMap<String, Long>();
        try
        {
            f = new JarFile(_moduleArchive);
            for (Enumeration<JarEntry> entries = f.entries(); entries.hasMoreElements(); )
            {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && !isHotDeployable(entry) && !isIgnoreable(entry))
                {
                    result.put(entry.getName(), entry.getTime());
                }
            }
            return result;
        }
        catch (IOException e)
        {
            return result;
        }
        finally
        {
            if (f != null) { try { f.close(); } catch (IOException e) {}}
        }
    }

    private File extractEntry(JarEntry entry, JarFile moduleArchive, File targetDir) throws IOException
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
        for (Map.Entry<File, Long> info : _libraryJarLastModifiedTimes.entrySet())
        {
            if (info.getKey().lastModified() != info.getValue().longValue())
            {
                return true;
            }
        }
        if (_moduleArchive.lastModified() != _moduleLastModifiedTime)
        {
            if (!calculateLastModifiedTimes().equals(_contentLastModifiedTime))
            {
                return true;
            }
            else
            {
                JarFile f = null;
                try
                {
                    f = new JarFile(_moduleArchive);

                    for (Enumeration<JarEntry> entries = f.entries(); entries.hasMoreElements(); )
                    {
                        JarEntry entry = entries.nextElement();
                        if (isHotDeployable(entry))
                        {
                            File jspDir = new File(_webappDir, "WEB-INF/jsp");
                            extractEntry(entry, f, jspDir);
                        }
                    }
                }
                catch (IOException e)
                {
                    return true;
                }
                finally
                {
                    if (f != null) { try { f.close(); } catch (IOException e) {} }
                }
            }
        }
        _moduleLastModifiedTime = _moduleArchive.lastModified();
        return false;
    }

    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        synchronized (_lock)
        {
            boolean holdsThisLock = Thread.holdsLock(this);

            // First, check if the class has already been loaded
            Class c = findLoadedClass(name);
            if (c == null)
            {
                try
                {
                    c = _parent.loadClass(name, false);
                }
                catch (ClassNotFoundException e)
                {
                    // If still not found, then invoke findClass in order
                    // to find the class.
                    c = findClass(name);
                }
            }
            if (resolve)
            {
                resolveClass(c);
            }
            return c;
        }
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        for (File f : _libraryJarLastModifiedTimes.keySet())
        {
            sb.append(f.getPath());
            sb.append(" ");
        }
        return sb.toString();
    }
}
