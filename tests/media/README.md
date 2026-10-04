# Public-domain movie images

These are real stills from six public-domain classic films. Each exact image was checked against Wikimedia Commons: `LicenseShortName` and `UsageTerms` are **Public domain**, and `Copyrighted` is **False**. [SOURCES.json](SOURCES.json) records the original download URL, file/license page, author credit, year, copyright-expiry evidence and SHA-256 hash. The source pages identify the applicable author-death/publication expiry; this statement applies to these selected images, not to modern remasters, music or replacement artwork.

| Film | Year | Image credit |
| --- | --- | --- |
| A Trip to the Moon | 1902 | Georges Méliès |
| The Great Train Robbery | 1903 | Edwin S. Porter |
| The Impossible Voyage | 1904 | Georges Méliès |
| The Kingdom of the Fairies | 1903 | Georges Méliès |
| Nosferatu | 1922 | F. W. Murnau / Prana Film |
| The Astronomer's Dream | 1898 | Georges Méliès |

The checked-in images retain the downloaded bytes and hashes. `scripts/create_test_assets.py` verifies them and derives proportional landscape/portrait crops for the mock Emby server. They provide distinct real film artwork for Home, categories, details and search. Nothing is bundled into the production APK; connected accounts continue to use their own server images.

For reproducible playback tests, the same script creates slow-zoom clips from the movie stills, with original sine-wave audio and SRT/ASS subtitles. These clips are **not the original movies**. BronyaTV dedicates its generated tones and subtitle text to the public domain under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). The HEVC clip's existing 10-bit/PQ configuration exercises the decoder path and is not a reference HDR master. The DTS fixture also uses a movie still with an original test tone. Generated media remains ignored under `tests/assets/`.

Signed-release screenshots use the compact six-film catalog (`small_catalog=1`) with distinct images and film metadata. Pagination tests use a separate synthetic 45-entry catalog.

Mock versions, synthetic pagination genres and sort names, library structure, cast labels, episode titles, extra catalog entries and progress are synthetic API contract data for filtering, pagination and playback tests. Screenshots show this fixture, not a production Emby library. Film names, dates and selected images come from the source records. Brief plot summaries are original project text based on the films; production pages display the connected server's actual data.
