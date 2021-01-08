# embedded
The LabKey server application can now be started with embedded tomcat.

#### Setup
1. Uncomment useEmbeddedTomcat, useLocalBuild and useSsl (if you have the local ssl setup, more instructions below) in root level gradle.propeties file
2. gradle refresh
3. pickpg/pickmssql
4. gradle cleanBuild, cleanDeploy and deployApp
5. Select LabKeyEmbbedded_Dev from IntelliJ run configurations

#### Embedded tomcat gradle properties explained:
+ useEmbeddedTomcat - if present, this will cause the :server:embedded project to be included in your local set of Gradle projects to be built.  This also will affect the behavior of the pickPG and deployApp tasks and is required to be present in order to build a distribution with an embedded Tomcat server.  This property is present in the root-level gradle.properties file, but commented out by default
+ useLocalBuild - If present, will allow the embedded server to use the locally built modules from build/deploy/modules.  This property is present in the root-level gradle.properties file, but commented out by default
+ useSsl - If present, the default port for the server will be 8443 instead of 8080 and the server.ssl properties in the application.properties file will be uncommented.  Note, however, that these ssl properties will still need to be updated by hand to reflect your local certificate location, password, etc.

Note that it is only the presence of these properties that matter, not the value. Setting one of these properties to false will not achieve what you expect. In addition, there is a new property serverPort that can be used to override the default port for your server instance.

#### Troubleshooting
+ If use classpath module of LabKeyEmbbedded_Dev configuration has no selected value and/or main class appears to be unresolved, try Invalidating Caches and Restart of IntelliJ
