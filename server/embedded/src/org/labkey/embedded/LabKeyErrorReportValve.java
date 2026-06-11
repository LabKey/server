package org.labkey.embedded;

import org.apache.catalina.valves.ErrorReportValve;

/** Issue 52415: Allow for Configuration of Default Error Page */
public class LabKeyErrorReportValve extends ErrorReportValve
{
    public LabKeyErrorReportValve()
    {
        // Don't show Tomcat version info on its 404 and other error pages
        setShowServerInfo(false);
    }
}
