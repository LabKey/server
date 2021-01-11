# embedded
LabKey Server can now be started with embedded Tomcat.

#### Setup
1. Uncomment useEmbeddedTomcat, useLocalBuild and useSsl (if you have the local ssl setup, more instructions below) in root level gradle.propeties file
2. gradle refresh
3. pickpg/pickmssql
4. gradle cleanBuild, cleanDeploy and deployApp
### Setup
1. Within the root-level `gradle.properties` file, uncomment the `useEmbeddedTomcat` and `useLocalBuild` properties.  If using SSL, also uncomment the `useSsl` property (and see further instructions below).
2. `gradlew cleanBuild`
3. `gradlew pickPg` or 'gradlew pickMSSQL`
4. `gradlew deployApp`
5. Within IntelliJ, do a Gradle Refresh in the Gradle Window
6. To start Tomcat within IntelliJ, select LabKeyEmbedded_Dev from the Run Configurations. (It will appear in a different section of the menu from the other LabKey configurations).

#### Embedded tomcat gradle properties explained:
+ `useEmbeddedTomcat` - if present, this will cause the :server:embedded project to be included in your local set of Gradle projects to be built.  This also will affect the behavior of the `pickPg`, `pickMSSQL`, and `deployApp` tasks and is required to be present in order to build a distribution with an embedded Tomcat server. This property is present in the root-level `gradle.properties` file, but commented out by default.
+ `useLocalBuild` - If present, will allow the embedded server to use the locally built modules from `build/deploy/modules`.  This property is present in the root-level `gradle.properties` file, but commented out by default.
+ `useSsl` - If present, the default port for the server will be 8443 instead of 8080 and the `server.ssl` properties in the `application.properties` file will be uncommented.  Note, however, that these ssl properties will still need to be updated by hand to reflect your local certificate location, password, etc.

Note that it is only the presence of these properties that matter, not the value. Setting one of these properties to false will not achieve what you expect. In addition, there is a new property, `serverPort`, that can be used to override the default port for your server instance.

#### Updated and new gradle tasks
The following tasks have been updated with new logic based on the presence of the `useEmbeddedTomcat` property:

+ `startTomcat` - If `useEmbeddedTomcat` is defined, starts the server using whatever executable jar is in the `build/deploy/embedded directory`.  If there is more than one jar file there, the task will fail.
+ `stopTomcat` - If `useEmbeddedTomcat` is defined, stops the server that is running on the port provided in `build/deploy/embedded/config/application.properties`.  This uses the Spring Actuator so if you have a non-embedded server running that does not have the actuator, this command will have no effect. The command logs a message to give an indication of whether it was successful or not.  For example:
> Task :server:stopEmbeddedTomcat
Shutdown successful
OR
> Task :server:stopEmbeddedTomcat
Shutdown command exited with non-zero status 7.

+ `deployDistribution` - If `useEmbeddedTomcat` is defined, this will find a distribution file with the `-embedded` suffix in its name and deploy it.  The deployment puts the executable jar file in `build/deploy/embedded` and copies the contents of the `bin` directory in the distribution to `build/deploy/embedded/bin`. Just like `deployDistribution`, this task has a dependency on the `setup` task, so after it has run the `build/deploy/embedded/configs` directory will also be populated with the current `application.properties` file, provided the `useEmbeddedTomcat` property has been set.

The following task has been added as well
+ `cleanEmbeddedDeploy` - This task is really only incidental.  It is a dependency of `deployDistribution` and `deployApp` so likely won’t be called individually.  It removes the `build/deploy/embedded` directory.


#### Troubleshooting
+ If use classpath module of LabKeyEmbedded_Dev configuration has no selected value and/or main class appears to be unresolved, try Invalidating Caches and Restart of IntelliJ
