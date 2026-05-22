package org.labkey.embedded;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@code ssm:/path/to/param} placeholder values in Spring properties at startup.
 *
 * <p>When a property value in {@code application.properties} begins with {@code ssm:}, this
 * post-processor fetches the actual value from AWS Systems Manager Parameter Store and injects
 * it as a high-priority property source before any {@code @ConfigurationProperties} beans are
 * bound. Example:
 *
 * <pre>
 * context.resources.jdbc.labkeyDataSource.password=ssm:/acme/web1/jdbc.json::password
 * context.encryptionKey=ssm:/acme/web1/encryptionKey
 * </pre>
 *
 * <p>A path without a leading {@code /} is relative and is resolved against
 * {@code context.awsParameterStore.prefix}, so the prefix only needs to be specified once:
 *
 * <pre>
 * context.awsParameterStore.prefix=/acme/web1/
 * context.resources.jdbc.labkeyDataSource.password=ssm:jdbc.json::password
 * context.encryptionKey=ssm:encryptionKey
 * </pre>
 *
 * <p>When an SSM parameter holds a JSON object, append {@code ::dotPath} to address a specific
 * key. Dot notation supports arbitrary nesting:
 *
 * <pre>
 * context.resources.jdbc.labkeyDataSource.password=ssm:jdbc.json::password
 * context.resources.jdbc.labkeyDataSource.host=ssm:jdbc.json::host
 * context.foo=ssm:config.json::level1.level2.value
 * </pre>
 *
 * <p>Multiple properties that reference different keys within the same JSON parameter will fetch
 * that parameter exactly once.
 *
 * <p>SSM initialization also runs when {@code context.awsParameterStore.prefix} is explicitly
 * configured (even with no {@code ssm:} values), so that the CloudServices module can create its
 * own {@code SsmClient} for on-demand {@code SecretService} lookups. When active, this processor
 * publishes {@code labkey.aws.ssm.region} and optionally {@code labkey.aws.ssm.secretsPrefix} as
 * JVM system properties without any cross-classloader reflection.
 *
 * <p>{@code context.awsParameterStore.secretsPrefix} controls where {@code SecretProperty}
 * values are looked up at runtime. A relative value (no leading {@code /}) is resolved against
 * the main prefix. A prefix ending with {@code ::} points to a JSON blob; the property name is
 * used as the key within that blob:
 *
 * <pre>
 * context.awsParameterStore.prefix=/acme/web1/
 * # Relative — effective prefix becomes /acme/web1/app-secrets.json::
 * context.awsParameterStore.secretsPrefix=app-secrets.json::
 * </pre>
 *
 * <p>On-premise deployments with no {@code ssm:} values and no explicit
 * {@code context.awsParameterStore.prefix} or {@code context.awsParameterStore.secretsPrefix}
 * configured incur zero overhead — no AWS SDK classes are initialized and no network calls are made.
 *
 * <p>Hard failures occur when:
 * <ul>
 *   <li>An {@code ssm:} reference names a parameter that does not exist in Parameter Store</li>
 *   <li>The AWS SDK cannot obtain credentials (no IAM role, no env vars, no profile)</li>
 *   <li>{@code context.awsParameterStore.prefix} or the resolved {@code secretsPrefix} does not
 *       end with {@code /} or {@code ::}</li>
 * </ul>
 */
@SuppressWarnings("SSBasedInspection") // This runs before Log4J is initialized, so use System.out and System.err
public class AwsParameterStoreEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered
{
    private static final String SSM_SCHEME = "ssm:";
    private static final String JSON_SEPARATOR = "::";
    private static final String PREFIX_PROPERTY = "context.awsParameterStore.prefix";
    private static final String SECRETS_PREFIX_PROPERTY = "context.awsParameterStore.secretsPrefix";
    private static final String REGION_PROPERTY = "context.awsParameterStore.region";
    private static final String DEFAULT_PREFIX = "/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Parsed reference to an SSM parameter, optionally with a dot-path into a JSON value. */
    private record SsmRef(String paramPath, String jsonPath) {}

    @Override
    public int getOrder()
    {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application)
    {
        String prefix = environment.getProperty(PREFIX_PROPERTY, DEFAULT_PREFIX);

        if (!prefix.endsWith("/") && !prefix.endsWith("::"))
            throw new IllegalStateException(
                "[LabKey AWS] " + PREFIX_PROPERTY + " must end with '/' or '::' (got: '" + prefix + "')");

        // Scan for "ssm:" references in the values of any property, which is an instruction to get the real value
        // from AWS's SSM parameter store
        Map<String, SsmRef> ssmRefs = findSsmReferences(environment, prefix);

        // Determine whether the operator explicitly opted into AWS config.
        boolean hasExplicitConfig = environment.containsProperty(PREFIX_PROPERTY) ||
                                    environment.containsProperty(SECRETS_PREFIX_PROPERTY);

        // On-premise deployments with no explicit AWS config and no ssm: values incur zero overhead.
        if (!hasExplicitConfig && ssmRefs.isEmpty())
            return;

        String secretsPrefix = environment.getProperty(SECRETS_PREFIX_PROPERTY);
        if (secretsPrefix != null)
        {
            // A relative path (no leading /) is resolved against the main prefix so operators
            // only need to configure the deployment prefix once.
            secretsPrefix = secretsPrefix.startsWith("/") ? secretsPrefix : prefix + secretsPrefix;
        }
        else
        {
            secretsPrefix = prefix;
        }

        if (!secretsPrefix.isEmpty() && !secretsPrefix.endsWith("/") && !secretsPrefix.endsWith("::"))
            throw new IllegalStateException(
                "[LabKey AWS] Resolved secretsPrefix must end with '/' or '::' (got: '" + secretsPrefix +
                "'). Check " + SECRETS_PREFIX_PROPERTY + " and " + PREFIX_PROPERTY + " in application.properties");

        String regionOverride = environment.getProperty(REGION_PROPERTY);
        Region region = resolveRegion(regionOverride);

        // Publish config as system properties so the CloudServices module can create its own
        // SsmClient for on-demand SecretProperty lookups via SecretService at runtime.
        System.setProperty("labkey.aws.ssm.region", region.id());
        System.setProperty("labkey.aws.ssm.secretsPrefix", secretsPrefix);

        if (ssmRefs.isEmpty())
            return;

        System.out.println("[LabKey AWS] Initializing SSM client (prefix=" + prefix + ")");

        try (SsmClient ssmClient = buildSsmClient(region))
        {
            Map<String, Object> resolved = new LinkedHashMap<>();
            // Fetch each unique SSM parameter path once to avoid redundant calls when multiple
            // properties reference different JSON keys within the same parameter.
            Map<String, String> fetchedParams = new LinkedHashMap<>();
            for (Map.Entry<String, SsmRef> entry : ssmRefs.entrySet())
            {
                String propName = entry.getKey();
                SsmRef ref = entry.getValue();
                String rawValue = fetchedParams.computeIfAbsent(ref.paramPath(), p -> fetchRequired(ssmClient, p));
                String resolvedValue = ref.jsonPath() != null ? extractFromJson(rawValue, ref.jsonPath(), ref.paramPath()) : rawValue;
                resolved.put(propName, resolvedValue);
                System.out.println("[LabKey AWS] Resolved property '" + propName + "' from SSM parameter '" + ref.paramPath() +
                    (ref.jsonPath() != null ? "::" + ref.jsonPath() : "") + "'");
            }
            environment.getPropertySources().addFirst(new MapPropertySource("awsParameterStore", resolved));
        }
    }

    private Map<String, SsmRef> findSsmReferences(ConfigurableEnvironment environment, String prefix)
    {
        Map<String, SsmRef> refs = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources())
        {
            if (source instanceof EnumerablePropertySource<?> enumerable)
            {
                for (String name : enumerable.getPropertyNames())
                {
                    Object raw = source.getProperty(name);
                    if (raw instanceof String str && str.startsWith(SSM_SCHEME))
                    {
                        SsmRef ref = getSsmRef(prefix, str);
                        refs.putIfAbsent(name, ref);
                    }
                }
            }
        }
        return refs;
    }

    private static @NonNull SsmRef getSsmRef(String prefix, String str)
    {
        String withoutScheme = str.substring(SSM_SCHEME.length());
        // A relative path (no leading /) is resolved against the configured prefix.
        if (!withoutScheme.startsWith("/"))
            withoutScheme = prefix + withoutScheme;
        int sepIdx = withoutScheme.indexOf(JSON_SEPARATOR);
        return sepIdx >= 0
            ? new SsmRef(withoutScheme.substring(0, sepIdx), withoutScheme.substring(sepIdx + JSON_SEPARATOR.length()))
            : new SsmRef(withoutScheme, null);
    }

    private String extractFromJson(String json, String dotPath, String paramName)
    {
        JsonNode node;
        try
        {
            node = OBJECT_MAPPER.readTree(json);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException(
                "[LabKey AWS] SSM parameter '" + paramName + "' value is not valid JSON: " + e.getMessage(), e);
        }
        for (String segment : dotPath.split("\\."))
        {
            node = node.get(segment);
            if (node == null)
                throw new IllegalStateException(
                    "[LabKey AWS] SSM parameter '" + paramName + "' JSON does not contain key path '" + dotPath + "' (missing segment '" + segment + "')");
        }
        if (!node.isValueNode())
            throw new IllegalStateException(
                "[LabKey AWS] SSM parameter '" + paramName + "' path '" + dotPath + "' does not resolve to a scalar value");
        return node.asText();
    }

    private Region resolveRegion(String regionOverride)
    {
        if (regionOverride != null && !regionOverride.isBlank())
        {
            Region region = Region.of(regionOverride);
            System.out.println("[LabKey AWS] Using explicit region: " + region);
            return region;
        }
        try
        {
            Region region = new DefaultAwsRegionProviderChain().getRegion();
            System.out.println("[LabKey AWS] Using auto-detected region: " + region +
                " (override with context.awsParameterStore.region or AWS_REGION env var)");
            return region;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(
                "[LabKey AWS] Failed to determine AWS region. Set context.awsParameterStore.region " +
                "in application.properties or the AWS_REGION environment variable: " + e.getMessage(), e);
        }
    }

    private SsmClient buildSsmClient(Region region)
    {
        try
        {
            return SsmClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        }
        catch (Exception e)
        {
            throw new IllegalStateException(
                "[LabKey AWS] Failed to initialize SSM client. Ensure AWS credentials are " +
                "available (IAM instance role, ECS task role, or environment variables): " +
                e.getMessage(), e);
        }
    }

    private String fetchRequired(SsmClient client, String paramName)
    {
        try
        {
            return client.getParameter(r -> r.name(paramName).withDecryption(true))
                .parameter().value();
        }
        catch (ParameterNotFoundException e)
        {
            throw new IllegalStateException(
                "[LabKey AWS] Required SSM parameter '" + paramName + "' was not found. " +
                "Create it in AWS Systems Manager > Parameter Store.", e);
        }
        catch (SsmException e)
        {
            throw new IllegalStateException(
                "[LabKey AWS] Failed to fetch SSM parameter '" + paramName + "': " + e.getMessage(), e);
        }
    }
}
