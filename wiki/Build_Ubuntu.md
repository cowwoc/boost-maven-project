# Instructions for building the plugin under Ubuntu

* Run `sudo apt-get install g++ libc6-dev-i386 lib32bz2-dev libbz2-dev lib32z1-dev zlib1g-dev g++-multilib`
* Install Maven3
    * Binaries: http://maven.apache.org/download.html
    * Instructions: http://maven.apache.org/download.html#Unix-based_Operating_Systems_Linux_Solaris_and_Mac_OS_X
    * Setting persistent environment variables: https://help.ubuntu.com/community/EnvironmentVariables#Persistent_environment_variables
* To clean a previous build, run `mvn -P<profile> clean:clean`
* To build binaries, run `mvn install`
* You're done!
