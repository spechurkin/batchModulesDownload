# Batch FoundryVTT module downloader

## Description

This project automates downloading and unpacking of module manifests for FoundryVTT. 
It fetches module JSON manifests from a list of URLs in a text file, downloads ZIP archives,
and unpacks them into the current working directory. Kotlin coroutines are used for asynchronous
operations, and Gson is used for JSON parsing.

## Features

- Supports GitHub, Dropbox, and Gist URLs with automatic direct link normalization.
- Caches downloaded JSON files to avoid redundant network calls.
- Concurrent downloading and unpacking with a limit on simultaneous operations.
- Error handling with invalid JSON saved for debugging.

## Usage
- Install [Java](https://www.java.com/en/download)
- Download `.jar` file from [Releases](https://github.com/spechurkin/batchModulesDownload/releases) page
- Place it in a directory you like
- Create `modules.txt` (you may download it and change)
- Run it