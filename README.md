# Foundry VTT module downloader for Perl

This standalone Perl project lives on `experimental/perl`. It reads module
manifest URLs, downloads ZIP archives and extracts each module into its own
directory. The Kotlin implementation remains on `master`.

## Installation

Use Perl 5.20 or newer built with thread support, such as Strawberry Perl on
Windows. Install runtime and test dependencies with cpanminus:

```sh
cpanm --installdeps .
```

Build and install using the standard Perl distribution workflow:

```sh
perl Makefile.PL
make
make test
make install
```

On Strawberry Perl, use `gmake` instead of `make`. Installation adds the
`foundry-modules` command to Perl's executable directory.

## Usage

```sh
perl bin/foundry-modules modules.txt --dir "path/to/modules"
perl bin/foundry-modules -d "path/to/modules"
perl bin/foundry-modules --help
```

After installation, run `foundry-modules` directly.

The links file defaults to `modules.txt`. Each line contains a `module.json`
URL; blank lines and lines beginning with `#` are ignored. GitHub blob, Gist
and Dropbox links are normalized to direct downloads.

`--dir DIRECTORY`, `-d DIRECTORY` and `--dir=DIRECTORY` select the extraction
directory, before or after the links filename. Missing directories are created.
Relative paths are resolved against the working directory. Without the option,
extraction uses the working directory. Quote paths containing spaces. Use `--`
before a links filename beginning with a dash.

ZIP archives and manifest cache files use the working directory. Each archive
is unpacked into `<directory>/<module-id>`. Populated module directories in the
selected destination are skipped on subsequent runs.

## Structure

- `bin/foundry-modules`: executable wrapper and UTF-8 output.
- `lib/App/FoundryModules.pm`: CLI parsing with `Getopt::Long` and application flow.
- `lib/App/FoundryModules/Downloader.pm`: HTTP, JSON manifests, SHA-256 cache keys
  and bounded worker queues using `threads` and `Thread::Queue`.
- `lib/App/FoundryModules/Archive.pm`: ZIP validation, staging, directory
  replacement and filesystem operations.
- `t/`: TAP tests for `prove` and `make test`.
- `Makefile.PL`, `cpanfile`, `MANIFEST`: distribution metadata and dependencies.

Manifests are native hash references decoded by `JSON::PP`. Supported fields
must contain scalar values.

Downloads use five workers, followed by five extraction workers. Duplicate URLs
and module IDs are processed once per run. Downloads are published from `.part`
files only after the response completes. ZIPs are extracted into unique staging
directories inside the destination, allowing extraction onto a different disk.
Existing modules are replaced only after extraction succeeds. Failed archives
are retained, and a failed module does not cancel the remaining jobs.

## Tests

```sh
prove -lr t
```

Tests use a local HTTP server and temporary working directories. They cover CLI
options, extraction paths, manifest validation, caching, failures, deduplication,
worker limits, interrupted downloads, unsafe ZIP entries and preservation of
existing modules when extraction fails.

Build and test a source distribution with `make disttest` after `perl Makefile.PL`.
