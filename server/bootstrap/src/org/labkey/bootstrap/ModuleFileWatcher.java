package org.labkey.bootstrap;

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
public class ModuleFileWatcher
{
    private Map<File, Long> _libraryJarLastModifiedTimes = new HashMap<File, Long>();

    private final Map<String, Long> _contentLastModifiedTime;
    private final File _moduleArchive;
    private final File _webappDir;
    private long _moduleLastModifiedTime;

    public ModuleFileWatcher(File moduleArchive, File webappDir) throws IOException
    {
        _moduleArchive = moduleArchive;
        _webappDir = webappDir;
        _moduleLastModifiedTime = _moduleArchive.lastModified();

        _contentLastModifiedTime = calculateLastModifiedTimes();
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

    private File getHotDeployLocation(JarEntry entry)
    {
        if (entry.isDirectory())
        {
            return null;
        }
        String name = entry.getName().toLowerCase();
        if (name.toLowerCase().startsWith("meta-inf/jsp"))
        {
            return new File(_webappDir, "WEB-INF/jsp");
        }
        return null;
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
                if (!entry.isDirectory() && getHotDeployLocation(entry) == null && !isIgnoreable(entry))
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

    public boolean modified()
    {
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
                        File location = getHotDeployLocation(entry);
                        if (location != null)
                        {
                            ModuleExtractor.extractEntry(entry, f, location);
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

    public void addLibraryJar(File extractedFile)
    {
        _libraryJarLastModifiedTimes.put(extractedFile, extractedFile.lastModified());
    }
}
