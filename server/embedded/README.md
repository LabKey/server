# Embedded
LabKey Server uses embedded Tomcat, which bundles a fully tested copy of Tomcat and simplifies configuration.

### Setup
1. Configure the server's properties as needed.
   + `useSsl` - If present, the default port for the server will be 8443 instead of 8080 and the `server.ssl` properties in the `application.properties` file will be uncommented. Note, however, that these ssl properties will still need to be updated by hand to reflect your local certificate location, password, etc.
   + Database connection properties should be configured in `server/configs/pg.properties`
2. `./gradlew pickPg`
3. `./gradlew deployApp`
4. Within IntelliJ, do a Gradle Refresh in the Gradle Window
5. To start Tomcat within IntelliJ, select `Spring Boot LabKey Embedded Tomcat Dev` from the Run Configurations. (It
   will appear in a different section of the menu from the other LabKey configurations.) If you are using the free
   Community Edition of IntelliJ which doesn't natively support Spring Boot, use the `LabKey Embedded Tomcat Dev`
   configuration instead.

#### Alternate ways to start/deploy the server:

+ `startLabKey` - Starts the server using whatever executable jar is in the `build/deploy/embedded directory`. If there is more than one jar file there, the task will fail.
+ `stopLabKey` - Stops the server with the process ID in `build/deploy/embedded/labkey.pid`. The command logs a message to give an indication of whether it was successful or not. For example:
> Task :server:stopLabKey
Shutdown successful
OR
> Task :server:stopLabKey
Shutdown command exited with non-zero status 7.

+ `deployDistribution` - Deploys a pre-built distribution file from (`dist/*.tar.gz`). The deployment puts the executable jar file in `build/deploy/embedded` and copies the contents of the `bin` directory in the distribution to `build/deploy/embedded/bin`. _Requires `useLocalBuild=false` in `gradle.properties` for server to run_

#### Troubleshooting
+ If starting your server from within IntelliJ fails, this is likely due to IntelliJ not being able to find the embedded project on which the configuration depends. There are a few things you should check:
  + Within the Gradle window, ensure that the `:server:embedded` project is listed. If it is not, run the task `gradle projects` on the command line to see if it appears in that listing. If it does, try a Gradle refresh within IntelliJ. If it is not in the output from the `projects` command, look at your `settings.gradle` file to see why this might be.
  + From the Configurations menu, choose the "Edit Configurations ..." and then under the Spring Boot section, choose the `Spring Boot LabKey Embedded Tomcat Dev` configuration (or `LabKey Embedded Tomcat Dev` if using IntelliJ Community Edition).
  + If there is nothing selected for "Use classpath of module", open the dropdown and choose `labkey-server.server.embedded.main`.
  + If there are no options presented for "Use classpath of module" and the embedded module does appear in the Gradle projects listing, try `File -> Invalidate Caches / Restart`
