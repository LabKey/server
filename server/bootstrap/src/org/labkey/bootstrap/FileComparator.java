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
import java.util.Comparator;

/*
* User: Dave
* Date: Dec 9, 2008
* Time: 10:54:30 AM
*/

/**
 * Compares two files (or directories)
 */
public class FileComparator extends BaseComparator implements Comparator<File>
{
    public int compare(File f1, File f2)
    {
        //first check lastmod (available for both files and directories)
        int ret = compareTimes(f1.lastModified(), f2.lastModified());

        //if still equal, check size if they are files (not directories)
        if(0 == ret && f1.isFile() && f2.isFile())
            ret = compareSizes(f1.length(), f2.length());

        return ret;
    }
}