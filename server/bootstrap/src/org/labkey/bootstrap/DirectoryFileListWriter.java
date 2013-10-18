/*
 * Copyright (c) 2006-2013 LabKey Corporation
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

import java.io.PrintWriter;
import java.io.File;

/**
 * User: jeckels
 * Date: Jun 12, 2006
 */
public class DirectoryFileListWriter
{
    public static final String API_FILES_LIST_RELATIVE_PATH = "WEB-INF/apiFiles.list";

    public static void main(String[] args) throws Exception
    {
        File listFile = new File(API_FILES_LIST_RELATIVE_PATH);
        try (PrintWriter writer = new PrintWriter(listFile))
        {
            // Flush to make sure the file's on disk before we list the directory contents
            writer.flush();
            File currentDir = new File(".");
            appendFileToList(currentDir, writer, currentDir.getAbsolutePath());
        }
    }

    private static void appendFileToList(File file, PrintWriter writer, String prefixToRemove)
    {
        String path = file.getAbsolutePath();
        if (!path.startsWith(prefixToRemove))
        {
            throw new IllegalArgumentException("Paths do not match: " + path + " and " + prefixToRemove);
        }
        path = path.substring(prefixToRemove.length());
        writer.println(path.replace('\\', '/'));
        if (file.isDirectory())
        {
            for (File content : file.listFiles())
            {
                appendFileToList(content, writer, prefixToRemove);
            }
        }
    }
}
