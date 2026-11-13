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

Both implementations use five download workers, followed by five extraction
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

Both versions accept `--dir DIRECTORY`, `-d DIRECTORY`, or `--dir=DIRECTORY`,
before or after the optional links file. The extraction directory is created if
needed; relative paths are resolved from the current working directory. Each
archive is unpacked into `<directory>/<module-id>`. Already populated module
directories there are skipped on subsequent runs. Quote paths containing spaces.
Without this option, extraction uses the current working directory.

## Kotlin tests

Run with JDK 17 or newer and Maven:

```sh
mvn test
```

The tests cover manifest parsing, URL list loading, HTTP downloads and caching,
CLI behavior, and ZIP extraction. They use a local HTTP server and separate JVMs
with temporary working directories, so downloads and caches stay out of the project directory.

## Perl version

The Perl implementation is located beside the Kotlin files in `src/main/kotlin`:
`Main.pl`, `ModuleData.pm`, and `ModuleManager.pm`.

Use a Perl build with thread support (for example, Strawberry Perl on Windows).
Install the HTTP, HTTPS, JSON, and ZIP dependencies if they are not already available:

```sh
cpan LWP::UserAgent LWP::Protocol::https JSON::PP IO::Uncompress::Unzip
```

Run from the directory where modules should be downloaded and unpacked:

```sh
perl src/main/kotlin/Main.pl modules.txt
perl src/main/kotlin/Main.pl modules.txt --dir "path/to/modules"
perl src/main/kotlin/Main.pl -d "path/to/modules"
```

The links file defaults to `modules.txt` when the argument is omitted. Downloads
and the manifest cache use the current working directory. `--dir` / `-d` changes
only the extraction destination, just as in the Kotlin version.

The integration tests use a local HTTP server and temporary directories:

```sh
cpan HTTP::Daemon IO::Compress::Zip
perl src/test/perl/module_manager.t
```

## Real modules integration test

This opt-in test downloads the actual archives referenced by `modules.txt` using
both CLIs. It checks source manifests and any manifests included in the archives,
compares every file by path and SHA-256, checks that archives and staging files
are removed, and runs both CLIs
again to verify already unpacked modules are skipped.

Build the JAR first, then run with Python 3.11 or newer, Java and Perl on `PATH`:

```sh
mvn package
python src/test/integration/real_modules.py
```

Use `--java`, `--perl`, `--jar` or `--modules` to override executable and input
paths. Each invocation creates separate Kotlin and Perl working directories,
logs and `result.json` under `target/real-modules/`.
