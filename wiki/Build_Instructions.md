# Building Boost

## Building Boost

### Pre-requisites

* Install Maven3
    * Binaries: http://maven.apache.org/download.html

## Build profiles

The first two profiles are "portable" and "api":

* "portable" must be run first, once per platform.
* "api" must be run after "portable", once per version across all platforms.

* The project contains the following "sources" profiles:
    * windows-sources
    * linux-sources
    * mac-sources

These profiles must be run after "portable" and "api", once per platform.

* The project contains the following "architecture" profiles:
    * windows-i386-msvc-debug
    * windows-i386-msvc-release
    * windows-amd64-msvc-debug
    * windows-amd64-msvc-release
    * linux-i386-gcc-debug
    * linux-i386-gcc-release
    * linux-amd64-gcc-debug
    * linux-amd64-gcc-release
    * mac-i386-gcc-debug
    * mac-i386-gcc-release
    * mac-amd64-gcc-debug
    * mac-amd64-gcc-release

These profiles must be run last.

* To delete the files from a previous build run `mvn -P<profile> clean:clean`
* To build a profile, run `mvn -P<profile> install`
* Example: `mvn -Plinux-i386-gcc-debug install`

## Building order

The profiles must be built in the following order: portable -> api -> sources -> architecture.

Examples:

* portable -> api -> windows-sources -> windows-i386-msvc-debug -> windows-i386-msvc-release
* portable -> linux-sources -> linux-i386-gcc-debug -> linux-amd64-gcc-debug

See [Build for Ubuntu](Build_for_Ubuntu.md) for Ubuntu-specific instructions.
