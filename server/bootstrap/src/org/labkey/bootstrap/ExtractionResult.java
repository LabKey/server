/*
 * Copyright (c) 2008 LabKey Corporation
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
import java.util.List;
import java.util.ArrayList;
import java.net.URL;
import java.net.MalformedURLException;

/**
 * User: jeckels
 * Date: Apr 10, 2008
 */
public class ExtractionResult
{
    private List<URL> _jarFileURLs;
    private List<File> _springConfigFiles;

    public ExtractionResult(List<File> jarFiles, List<File> springConfigFiles)
    {
        _springConfigFiles = springConfigFiles;

        _jarFileURLs = new ArrayList<URL>();
        for (File jarFile : jarFiles)
        {
            try
            {
                _jarFileURLs.add(jarFile.toURI().toURL());
            }
            catch (MalformedURLException e)
            {
                throw new RuntimeException(e);
            }
        }

    }

    public List<URL> getJarFileURLs()
    {
        return _jarFileURLs;
    }

    public List<File> getSpringConfigFiles()
    {
        return _springConfigFiles;
    }
}
