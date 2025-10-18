# Batch FoundryVTT module downloader

## Description

This project automates downloading and unpacking of module manifests for FoundryVTT.
It fetches module JSON manifests from a list of URLs in a text file, downloads ZIP archives,
and unpacks them into the current working directory. Kotlin coroutines are used for asynchronous
operations, and Gson is used for JSON parsing.

## Features

- Supports any link to `module.json` (open an issue if not) with automatic direct link normalization for GitHub,
  Dropbox, and Gist URLs.
- Caches downloaded JSON files to avoid redundant network calls.
- Concurrent downloading and unpacking with a limit on simultaneous operations.

## Usage

- Install [Java](https://www.java.com/en/download)
- Download `.jar` file from [Releases](https://github.com/spechurkin/batchModulesDownload/releases) page
- Place it in a directory you like
- Create `modules.txt` (you may download it and change) and place some links you want
- Run it