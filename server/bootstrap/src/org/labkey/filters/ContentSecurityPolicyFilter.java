package org.labkey.filters;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Enumeration;


/** example usage,

 very strict, disallows 'external' websites, disallows unsafe-inline, but only reports violations (does not enforce)
 good for test automation!

  <pre>
      <filter>
        <filter-name>Content Security Policy Filter Filter</filter-name>
        <filter-class>org.labkey.filters.ContentSecurityPolicyFilter</filter-class>
        <init-param>
          <param-name>policy</param-name>
          <param-value>
            default-src 'self';
            connect-src 'self' ;
            object-src 'none' ;
            style-src 'self' 'unsafe-inline' ;
            img-src 'self' data: ;
            script-src 'unsafe-eval' 'strict-dynamic' 'nonce-${REQUEST.SCRIPT.NONCE}';
            base-uri 'self' ;
            upgrade-insecure-requests ;
            frame-ancestors 'self' ;
            report-to /labkey/admin-contentsecuritypolicyreport.api ;
            report-uri /labkey/admin-contentsecuritypolicyreport.api ;
          </param-value>
        </init-param>
        <init-param>
          <param-name>disposition</param-name>
          <param-value>report</param-value>
        </init-param>
      </filter>
      <filter-mapping>
        <filter-name>Content Security Policy Filter Filter</filter-name>
        <url-pattern>/*</url-pattern>
      </filter-mapping>
  </pre>

  less strict but enforces directives, (NOTE: unsafe-inline is still required for many modules)

  <pre>
      <filter>
        <filter-name>Content Security Policy Filter Filter</filter-name>
        <filter-class>org.labkey.filters.ContentSecurityPolicyFilter</filter-class>
        <init-param>
          <param-name>policy</param-name>
          <param-value>
            default-src 'self' https: ;
            connect-src 'self' https: ;
            object-src 'none' ;
            style-src 'self' https: 'unsafe-inline' ;
            img-src 'self' data: ;
            script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' 'nonce-${REQUEST.SCRIPT.NONCE}';
            base-uri 'self' ;
            upgrade-insecure-requests ;
            frame-ancestors 'self' ;
            report-to /labkey/admin-contentsecuritypolicyreport.api ;
            report-uri /labkey/admin-contentsecuritypolicyreport.api ;
          </param-value>
        </init-param>
        <init-param>
          <param-name>disposition</param-name>
          <param-value>enforce</param-value>
        </init-param>
      </filter>
      <filter-mapping>
        <filter-name>Content Security Policy Filter Filter</filter-name>
        <url-pattern>/*</url-pattern>
      </filter-mapping>
  </pre>

 Do not copy-and-paste these examples for any production environment without understanding the meaning of each directive!
 */


public class ContentSecurityPolicyFilter implements Filter
{
    private static final String NONCE_SUBST = "${REQUEST.SCRIPT.NONCE}";
    private static final String HEADER_NONCE = "org.labkey.filters.ContentSecurityPolicyFilter#NONCE";  // needs to match PageConfig.HEADER_NONCE
    private static final String CONTENT_SECURITY_POLICY_HEADER_NAME = "Content-Security-Policy";
    private static final String CONTENT_SECURITY_POLICY_REPORT_ONLY_HEADER_NAME = "Content-Security-Policy-Report-Only";
    private String policy = "";
    private int nonceSubstIndex = -1;

    private boolean reportOnly = false;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        Enumeration<String> paramNames = filterConfig.getInitParameterNames();
        while (paramNames.hasMoreElements())
        {
            String paramName = paramNames.nextElement();
            String paramValue = filterConfig.getInitParameter(paramName);
            if ("policy".equalsIgnoreCase(paramName))
            {
                String s = paramValue.trim();
                s = s.replace( '\n', ' ' );
                s = s.replace( '\r', ' ' );
                s = s.replace( '\t', ' ' );
                s = s.replace((char)0x2018, (char)0x027);     // LEFT SINGLE QUOTATION MARK -> APOSTROPHE
                s = s.replace((char)0x2019, (char)0x027);     // RIGHT SINGLE QUOTATION MARK -> APOSTROPHE
                policy = s;
                nonceSubstIndex = policy.indexOf(NONCE_SUBST);
            }
            else if ("disposition".equalsIgnoreCase(paramName))
            {
                String s = paramValue.trim();
                if (!"report".equalsIgnoreCase(s) && !"enforce".equalsIgnoreCase(s))
                    throw new ServletException("ContentSecurityPolicyFilter is misconfigured, unexpected disposition value: " + s);
                reportOnly = "report".equalsIgnoreCase(s);
            }
            else
            {
                throw new ServletException("ContentSecurityPolicyFilter is misconfigured, unexpected parameter name: " + paramName);
            }
        }
    }


    @Override
    public void destroy()
    {
    }


    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (request instanceof HttpServletRequest req && response instanceof HttpServletResponse resp && null != policy && policy.length()>0)
        {
            var csp = policy;
            if (nonceSubstIndex != -1)
                csp = csp.substring(0,nonceSubstIndex) + getScriptNonceHeader(req) + csp.substring(nonceSubstIndex + NONCE_SUBST.length());
            var header = reportOnly ? CONTENT_SECURITY_POLICY_REPORT_ONLY_HEADER_NAME : CONTENT_SECURITY_POLICY_HEADER_NAME;
            resp.setHeader(header, csp);
        }
        chain.doFilter(request, response);
    }


    public static String getScriptNonceHeader(HttpServletRequest request)
    {
        String nonce = (String)request.getAttribute(HEADER_NONCE);
        if (nonce != null)
            return nonce;

        nonce = Long.toHexString(rand.nextLong());
        rand.setSeed(request.getRequestURI().hashCode());

        request.setAttribute(HEADER_NONCE, nonce);
        return nonce;
    }

    private static final SecureRandom rand = new SecureRandom();
}