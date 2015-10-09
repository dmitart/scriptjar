# scriptjar

This script converts Groovy scripts to JVM executable JAR. It packages all Grab dependencies and Groovy lib, so it can
be run without Groovy on target machine. It is also compatible with AWS Lambda. Main class name is the same as original script name.
Script detects Groovy location via GROOVY_HOME.

Usage:
```
./scriptjar.groovy input.groovy output.jar
```
