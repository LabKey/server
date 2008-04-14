package org.labkey.bootstrap;

import java.io.File;
import java.util.List;

/**
 * User: jeckels
 * Date: Apr 10, 2008
 */
public class ExtractionResult
{
    private List<File> _jarFiles;
    private List<File> _springConfigFiles;

    public ExtractionResult(List<File> jarFiles, List<File> springConfigFiles)
    {
        _jarFiles = jarFiles;
        _springConfigFiles = springConfigFiles;
    }

    public List<File> getJarFiles()
    {
        return _jarFiles;
    }

    public List<File> getSpringConfigFiles()
    {
        return _springConfigFiles;
    }
}
