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

import java.util.jar.JarEntry;
import java.util.Comparator;
import java.io.File;

/*
* User: Dave
* Date: Dec 9, 2008
* Time: 10:58:14 AM
*/
public class JarEntryComparator extends BaseComparator implements Comparator<JarEntry>
{
    public int compare(JarEntry e1, JarEntry e2)
    {
        //first check lastmod (available for both files and directories)
        int ret = compareTimes(e1.getTime(), e2.getTime());

        //if still equal, check size if they are files (not directories)
        if(0 == ret && !e1.isDirectory() && !e2.isDirectory())
            ret = compareSizes(e1.getSize(), e2.getSize());

        return ret;
    }

    /**
     * Compares a JarEntry against a target file in the file system
     * @param e the JarEntry
     * @param f the target file
     * @return 0 if e == f; -1 if e < f; 1 if e > f
     */
    public int compare(JarEntry e, File f)
    {
        if(!f.exists())
            return -1;

        //first check lastmod (available for both files and directories)
        int ret = compareTimes(e.getTime(), f.lastModified());

        //if still equal, check size if they are files (not directories)
        if(0 == ret && !e.isDirectory() && !f.isDirectory())
            ret = compareSizes(e.getSize(), f.length());

        return ret;
    }
}