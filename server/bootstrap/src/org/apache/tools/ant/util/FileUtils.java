/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.tools.ant.util;

import org.apache.tools.ant.BuildException;

import java.io.File;
import java.util.Locale;
import java.util.Stack;
import java.util.StringTokenizer;

/**
 * LabKey's stripped down version of ant's FileUtils. Tomcat's JspC requires this class; providing it and a few others in
 * our bootstrap jar eliminates the need to distribute and deploy ant.jar into the /tomcat/lib directory.
 */
public class FileUtils
{
    private static final FileUtils PRIMARY_INSTANCE = new FileUtils();

    // This portion of FileUtils used org.apache.tools.ant.taskdefs.condition.Os to determine a bunch of OS system
    // constants, for use in subsequent code (e.g., resolveFile()). These simplified versions should be identical.
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase(Locale.US);
    private static final String PATH_SEP = System.getProperty("path.separator");
    private static boolean onNetWare = OS_NAME.contains("netware");     // Essentially what Os.isFamily("netware") does
    private static boolean onDos = PATH_SEP.equals(";") && !onNetWare;  // Essentially what Os.isFamily("dos") does

    /**
     * Method to retrieve The FileUtils, which is shared by all users of this
     * method.
     * @return an instance of FileUtils.
     * @since Ant 1.6.3
     */
    @SuppressWarnings("unused")  // Called by Tomcat's JspC
    public static FileUtils getFileUtils() {
        return PRIMARY_INSTANCE;
    }

    /**
     * Empty constructor.
     */
    protected FileUtils() {
    }

    /**
     * Interpret the filename as a file relative to the given file
     * unless the filename already represents an absolute filename.
     * Differs from <code>new File(file, filename)</code> in that
     * the resulting File's path will always be a normalized,
     * absolute pathname.  Also, if it is determined that
     * <code>filename</code> is context-relative, <code>file</code>
     * will be discarded and the reference will be resolved using
     * available context/state information about the filesystem.
     *
     * @param file the "reference" file for relative paths. This
     * instance must be an absolute file and must not contain
     * &quot;./&quot; or &quot;../&quot; sequences (same for \ instead
     * of /).  If it is null, this call is equivalent to
     * <code>new java.io.File(filename).getAbsoluteFile()</code>.
     *
     * @param filename a file name.
     *
     * @return an absolute file.
     * @throws java.lang.NullPointerException if filename is null.
     */
    @SuppressWarnings("unused")  // Called by Tomcat's JspC
    public File resolveFile(File file, String filename) {
        if (!isAbsolutePath(filename)) {
            char sep = File.separatorChar;
            filename = filename.replace('/', sep).replace('\\', sep);
            if (isContextRelativePath(filename)) {
                file = null;
                // on cygwin, our current directory can be a UNC;
                // assume user.dir is absolute or all hell breaks loose...
                String udir = System.getProperty("user.dir");
                if (filename.charAt(0) == sep && udir.charAt(0) == sep) {
                    filename = dissect(udir)[0] + filename.substring(1);
                }
            }
            filename = new File(file, filename).getAbsolutePath();
        }
        return normalize(filename);
    }

    /**
     * On DOS and NetWare, the evaluation of certain file
     * specifications is context-dependent.  These are filenames
     * beginning with a single separator (relative to current root directory)
     * and filenames with a drive specification and no intervening separator
     * (relative to current directory of the specified root).
     * @param filename the filename to evaluate.
     * @return true if the filename is relative to system context.
     * @throws java.lang.NullPointerException if filename is null.
     * @since Ant 1.7
     */
    public static boolean isContextRelativePath(String filename) {
        if (!(onDos || onNetWare) || filename.length() == 0) {
            return false;
        }
        char sep = File.separatorChar;
        filename = filename.replace('/', sep).replace('\\', sep);
        char c = filename.charAt(0);
        int len = filename.length();
        return (c == sep && (len == 1 || filename.charAt(1) != sep))
                || (Character.isLetter(c) && len > 1
                && filename.indexOf(':') == 1
                && (len == 2 || filename.charAt(2) != sep));
    }

    /**
     * Verifies that the specified filename represents an absolute path.
     * Differs from new java.io.File("filename").isAbsolute() in that a path
     * beginning with a double file separator--signifying a Windows UNC--must
     * at minimum match "\\a\b" to be considered an absolute path.
     * @param filename the filename to be checked.
     * @return true if the filename represents an absolute path.
     * @throws java.lang.NullPointerException if filename is null.
     * @since Ant 1.6.3
     */
    public static boolean isAbsolutePath(String filename) {
        int len = filename.length();
        if (len == 0) {
            return false;
        }
        char sep = File.separatorChar;
        filename = filename.replace('/', sep).replace('\\', sep);
        char c = filename.charAt(0);
        if (!(onDos || onNetWare)) {
            return (c == sep);
        }
        if (c == sep) {
            if (!(onDos && len > 4 && filename.charAt(1) == sep)) {
                return false;
            }
            int nextsep = filename.indexOf(sep, 2);
            return nextsep > 2 && nextsep + 1 < len;
        }
        int colon = filename.indexOf(':');
        return (Character.isLetter(c) && colon == 1
                && filename.length() > 2 && filename.charAt(2) == sep)
                || (onNetWare && colon > 0);
    }

    /**
     * &quot;Normalize&quot; the given absolute path.
     *
     * <p>This includes:
     * <ul>
     *   <li>Uppercase the drive letter if there is one.</li>
     *   <li>Remove redundant slashes after the drive spec.</li>
     *   <li>Resolve all ./, .\, ../ and ..\ sequences.</li>
     *   <li>DOS style paths that start with a drive letter will have
     *     \ as the separator.</li>
     * </ul>
     * Unlike {@link File#getCanonicalPath()} this method
     * specifically does not resolve symbolic links.
     *
     * @param path the path to be normalized.
     * @return the normalized version of the path.
     *
     * @throws java.lang.NullPointerException if path is null.
     */
    public File normalize(final String path) {
        Stack s = new Stack();
        String[] dissect = dissect(path);
        s.push(dissect[0]);

        StringTokenizer tok = new StringTokenizer(dissect[1], File.separator);
        while (tok.hasMoreTokens()) {
            String thisToken = tok.nextToken();
            if (".".equals(thisToken)) {
                continue;
            } else if ("..".equals(thisToken)) {
                if (s.size() < 2) {
                    // Cannot resolve it, so skip it.
                    return new File(path);
                }
                s.pop();
            } else { // plain component
                s.push(thisToken);
            }
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.size(); i++) {
            if (i > 1) {
                // not before the filesystem root and not after it, since root
                // already contains one
                sb.append(File.separatorChar);
            }
            sb.append(s.elementAt(i));
        }
        return new File(sb.toString());
    }

    /**
     * Dissect the specified absolute path.
     * @param path the path to dissect.
     * @return String[] {root, remaining path}.
     * @throws java.lang.NullPointerException if path is null.
     * @since Ant 1.7
     */
    public String[] dissect(String path) {
        char sep = File.separatorChar;
        path = path.replace('/', sep).replace('\\', sep);

        // make sure we are dealing with an absolute path
        if (!isAbsolutePath(path)) {
            throw new BuildException(path + " is not an absolute path");
        }
        String root = null;
        int colon = path.indexOf(':');
        if (colon > 0 && (onDos || onNetWare)) {

            int next = colon + 1;
            root = path.substring(0, next);
            char[] ca = path.toCharArray();
            root += sep;
            //remove the initial separator; the root has it.
            next = (ca[next] == sep) ? next + 1 : next;

            StringBuffer sbPath = new StringBuffer();
            // Eliminate consecutive slashes after the drive spec:
            for (int i = next; i < ca.length; i++) {
                if (ca[i] != sep || ca[i - 1] != sep) {
                    sbPath.append(ca[i]);
                }
            }
            path = sbPath.toString();
        } else if (path.length() > 1 && path.charAt(1) == sep) {
            // UNC drive
            int nextsep = path.indexOf(sep, 2);
            nextsep = path.indexOf(sep, nextsep + 1);
            root = (nextsep > 2) ? path.substring(0, nextsep + 1) : path;
            path = path.substring(root.length());
        } else {
            root = File.separator;
            path = path.substring(1);
        }
        return new String[] {root, path};
    }
}
