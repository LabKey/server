package org.labkey.bootstrap;

import java.io.PrintWriter;
import java.io.File;

/**
 * User: jeckels
 * Date: Jun 12, 2006
 */
public class DirectoryFileListWriter
{
    public static void main(String[] args) throws Exception
    {
        PrintWriter writer = null;
        try
        {
            File listFile = new File("./WEB-INF/apiFiles.list");
            writer = new PrintWriter(listFile);
            // Flush to make sure the file's on disk before we list the directory contents
            writer.flush();
            File currentDir = new File(".");
            appendFileToList(currentDir, writer, currentDir.getAbsolutePath());
        }
        finally
        {
            if (writer != null)
            {
                writer.close();
            }
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
