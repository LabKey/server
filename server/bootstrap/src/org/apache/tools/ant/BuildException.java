package org.apache.tools.ant;

/**
 * LabKey's stub version of ant's BuildException. Tomcat's JspC requires this class; providing it and a few others in our
 * bootstrap jar eliminates the need to distribute and deploy ant.jar into the /tomcat/lib directory.
 *
 * Created by adam on 5/27/2017.
 */
@SuppressWarnings("unused")
public class BuildException extends RuntimeException
{
    public BuildException(String message) {
        super(message);
    }

    public BuildException(Throwable cause) {
        super(cause.toString());
    }
}