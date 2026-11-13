# Batch FoundryVTT module downloader

## Description

This project automates downloading and unpacking of module manifests for FoundryVTT.
It fetches module JSON manifests from a list of URLs in a text file, downloads ZIP archives,
and unpacks them into the selected directory (the current working directory by default). Kotlin coroutines are used for asynchronous
operations, and Gson is used for JSON parsing.

## Features

- Supports any link to `module.json` (open an issue if not) with automatic direct link normalization for GitHub,
  Dropbox, and Gist URLs.
- Caches downloaded JSON files to avoid redundant network calls.
- Concurrent downloading and unpacking with a limit on simultaneous operations.

The implementation uses five download workers, followed by five extraction
workers. Repeated URLs and module IDs are processed once per run. Each worker
takes the next queued job as soon as it finishes its current job.

Downloads are written to `.part` files and renamed only after the HTTP response
has completed. ZIPs are extracted into unique staging directories; an existing
module is replaced only after extraction succeeds. Failed archives are retained,
and a failed module does not cancel the remaining jobs.

## Usage

- Install [Java](https://www.java.com/en/download)
- Download `.jar` file from [Releases](https://github.com/spechurkin/batchModulesDownload/releases) page
- Place it in a directory you like
- Create `modules.txt` (you may download it and change) and place some links you want
- Run it

```sh
java -jar batchModulesDownload.jar modules.txt --dir "path/to/modules"
java -jar batchModulesDownload.jar -d "path/to/modules"
```

The CLI accepts `--dir DIRECTORY`, `-d DIRECTORY`, or `--dir=DIRECTORY`,
before or after the optional links file. The extraction directory is created if
needed; relative paths are resolved from the current working directory. Each
archive is unpacked into `<directory>/<module-id>`. Already populated module
directories there are skipped on subsequent runs. Quote paths containing spaces.
Without this option, extraction uses the current working directory.

## Kotlin build and release

Build the executable JAR, including its dependencies, with JDK 17 or newer and Maven:

```sh
mvn --batch-mode --no-transfer-progress clean verify
java -jar target/batchModulesDownload.jar modules.txt --dir "path/to/modules"
```

The [Kotlin workflow](.github/workflows/kotlin.yml) builds and tests once on Linux
for branch pushes and pull requests. The executable JAR is the same for Windows,
Linux, and macOS with a compatible JVM. The workflow also checks that the packaged
JAR starts, then saves the JAR, its SHA-256 checksum, and test reports as workflow
artifacts for 14 days. A build can also be started manually from the Actions tab.

To publish a release, set the project version in `pom.xml`, commit the changes,
and push a matching tag. For the current `1.0.0` version:

```sh
git tag -a v1.0.0 -m "release 1.0.0"
git push origin v1.0.0
```

After the build and tests pass, the workflow publishes a GitHub Release with
`batchModulesDownload.jar`, `batchModulesDownload.jar.sha256`, and generated
release notes. It publishes the tested platform-independent JAR without rebuilding it.
Tags must match `v` followed by the POM version; SNAPSHOT versions are rejected.
Versions containing a hyphen, such as `1.1.0-rc.1`, are marked as prereleases.
An existing release is not overwritten. Rerunning a published release's job fails;
publish a new version and tag for a replacement.

Publishing uses the built-in `GITHUB_TOKEN` with `contents: write` scoped to the
release job. No additional secret is needed. Manual and branch builds only
produce workflow artifacts.

To verify a downloaded package on Linux or with Git Bash:

```sh
sha256sum --check batchModulesDownload.jar.sha256
```

## Kotlin tests

Run with JDK 17 or newer and Maven:

```sh
mvn test
```

The tests cover manifest parsing, URL list loading, HTTP downloads and caching,
CLI behavior, and ZIP extraction. They use a local HTTP server and separate JVMs
with temporary working directories, so downloads and caches stay out of the project directory.

## Perl version

The standalone Perl implementation is maintained on the
[`experimental/perl` branch](https://github.com/spechurkin/batchModulesDownload/tree/experimental/perl).
It builds a standalone CLI executable with Perl and dependencies bundled.
The default `modules.txt` and extraction directory are beside the executable;
`--modules` / `-m` and `--dir` / `-d` override those paths.
