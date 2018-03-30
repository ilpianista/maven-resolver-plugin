resolver-maven-plugin
=====================

[![Build Status](https://gitlab.com/ilpianista/resolver-maven-plugin/badges/master/build.svg)](https://gitlab.com/ilpianista/resolver-maven-plugin/pipelines)

A Maven Plugin to add additionals JARs (and their dependencies) to the WAR file.

JARs are downloaded from the Maven repositories.

```
     <plugin>
       <groupId>it.andreascarpino.maven</groupId>
       <artifactId>resolver-maven-plugin</artifactId>
       <version>0.0.1-SNAPSHOT</version>
       <configuration>
         <artifacts>
            <artifact>
               <groupId>com.oracle</groupId>
               <artifactId>ojdbc8</artifactId>
               <version>12.2.0.1</version>
            </artifact>
         </artifacts>
       </configuration>
       <executions>
         <execution>
           <phase>package</phase>
           <goals>
             <goal>resolve</goal>
           </goals>
         </execution>
       </executions>
     </plugin>
```

# Build

    $ git clone https://gitlab.com/ilpianista/resolver-maven-plugin.git
    $ cd resolver-maven-plugin
    $ mvn package

## Donate

Donations via [Liberapay](https://liberapay.com/ilpianista) or Bitcoin (1Ph3hFEoQaD4PK6MhL3kBNNh9FZFBfisEH) are always welcomed, _thank you_!

# License

MIT

Inspired by the [maven-dependency-plugin](https://gitbox.apache.org/repos/asf?p=maven-dependency-plugin.git).
