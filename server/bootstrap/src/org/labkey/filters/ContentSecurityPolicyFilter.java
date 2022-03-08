package org.labkey.filters;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;


/** example usage
 * <pre>
 *     <filter>
 *       <filter-name>Content Security Policy Filter Filter</filter-name>
 *       <filter-class>org.labkey.filters.ContentSecurityPolicyFilter</filter-class>
 *       <init-param>
 *         <param-name>policy</param-name>
 *         <param-value>
 *             default-src https: http: ;
 *             connect-src https: http: wss: ws: ;
 *             object-src : 'none ;
 *             script-src : https: http: 'unsafe-inline' 'unsafe-eval' ;
 *             style-src : https: http: 'unsafe-inline' ;
 *             base-uri : 'self' ;
 *             upgrade-insecure-requests ;
 *         </param-value>
 *       </init-param>
 *     </filter>
 *     <filter-mapping>
 *       <filter-name>Content Security Policy Filter Filter</filter-name>
 *       <url-pattern>/*</url-pattern>
 *     </filter-mapping>
 * </pre>
 */


public class ContentSecurityPolicyFilter implements Filter
{
    private static final String CONTENT_SECURITY_POLICY_HEADER_NAME = "Content-Security-Policy";
    private String policy = "";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        Enumeration<String> paramNames = filterConfig.getInitParameterNames();
        while (paramNames.hasMoreElements())
        {
            String paramName = paramNames.nextElement();
            String paramValue = filterConfig.getInitParameter(paramName);
            if ("policy".equals(paramName))
            {
                policy = policy.replace('\n', ' ');
                policy = policy.replace( '\r', ' ');
                policy = paramValue.trim();
            }
            else
            {
                throw new ServletException("ContentSecurityPolicyFilter is configured, unexpected parameter name: " + paramName);
            }
        }
    }


    @Override
    public void destroy()
    {
    }


    // This is the first (and last) LabKey code invoked on a request.
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (response instanceof HttpServletResponse resp && null != policy && policy.length()>0)
        {
            resp.setHeader(CONTENT_SECURITY_POLICY_HEADER_NAME, policy);
        }
        chain.doFilter(request, response);
    }
}