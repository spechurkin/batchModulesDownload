# Foundry VTT module downloader

A standalone console application for downloading and extracting Foundry VTT
modules. The `experimental/perl` branch contains its Perl source and executable
build tools. End users do not need Perl, CPAN or Java.

## Run the application

Extract the build ZIP into a writable folder. Keep `modules.txt` beside
`foundry-modules.exe`, put one manifest URL on each line, and launch the executable.
Blank lines and lines beginning with `#` are ignored. The application downloads
the archives and unpacks modules beside the executable. Already populated module
directories are skipped when it runs again.

On Windows, launch `foundry-modules.exe` directly or run it from a terminal:

```powershell
.\foundry-modules.exe
```

The default `modules.txt` is always found beside the executable, including when
the terminal's current directory is elsewhere. Downloads and the manifest cache
also use the executable's directory.

To choose another links file or extraction directory:

```powershell
.\foundry-modules.exe "D:\lists\modules.txt" --dir "D:\Foundry\modules"
.\foundry-modules.exe --modules "D:\lists\modules.txt" -d "D:\Foundry\modules"
.\foundry-modules.exe -m "D:\lists\modules.txt" -d "D:\Foundry\modules"
.\foundry-modules.exe --help
```

`--modules` / `-m` and the positional filename are equivalent. `--dir` / `-d`
selects the extraction directory and creates it if necessary. `--dir=PATH` is
also accepted. Explicit relative paths are resolved from the terminal's working
directory. Each module is extracted into `<directory>/<module-id>`.

Linux and macOS builds use the command `./foundry-modules` with the same options.
Use `chmod +x foundry-modules` if extracting the artifact loses its executable bit.
Each operating system and processor architecture needs its own build.

## Build from source

The following steps are for developers. Build with Perl 5.20 or newer with
thread support; Strawberry Perl works on Windows.

```sh
cpanm --with-develop --installdeps .
prove -lr t
perl script/build.pl
perl script/test-binary.pl dist/foundry-modules.exe
```

On Linux and macOS, pass `dist/foundry-modules` to the binary test. The build
uses [PAR::Packer](https://metacpan.org/pod/pp) to bundle the interpreter, modules,
TLS support and certificate authorities into a console executable. Output files
go into `dist/`. `--output FILE` overrides the executable's output path.

The binary test copies the executable into a temporary folder, removes Perl
from its `PATH` and checks CLI parsing, HTTP downloads, extraction, repeat runs
and path overrides. It also launches from a different directory to verify that
the default list and output stay beside the executable.

The `build-cli` GitHub Actions workflow builds and tests separate Windows x64,
Linux x64, macOS x64 and macOS arm64 artifacts. Download the artifact for the
target platform after the workflow succeeds.

## Source layout

- `bin/foundry-modules`: console entry point and UTF-8 output.
- `lib/App/FoundryModules.pm`: `Getopt::Long` options, default paths and execution.
- `lib/App/FoundryModules/Downloader.pm`: HTTP, JSON, SHA-256 cache keys and
  worker queues using `threads` and `Thread::Queue`.
- `lib/App/FoundryModules/Archive.pm`: ZIP validation, staging and publication.
- `script/`: standalone executable build and isolated binary tests.
- `t/`: TAP tests using a local HTTP server and temporary directories.
- `cpanfile`: source and build dependencies.

Downloads use five workers, followed by five extraction workers. Duplicate URLs
and module IDs are processed once per run. Partial downloads are published only
after the response completes. Extraction uses unique staging directories on the
destination disk. Existing modules are replaced only after successful extraction.
Failed archives are retained, and a failed module does not cancel the remaining
jobs. Manifests are ordinary Perl hash references decoded with `JSON::PP`.
