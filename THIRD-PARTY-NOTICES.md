# Third-party notices

Dailies itself is under the MIT licence (see `LICENSE`). Its jar also carries the video encoder below, run as
a separate program, and the weights of a small sound classifier (see "YAMNet" at the end). This file and the licence texts it names are inside the jar too: this file at the top, the texts
in the `licenses` folder.

## Platforms

A video encoder is packed in for **Windows (64-bit Intel or AMD), Linux (64-bit Intel or AMD, and ARM) and macOS
(Apple silicon and Intel)**: `windows-x86_64`, `linux-x86_64`, `linux-arm64`, `macosx-arm64` and `macosx-x86_64`. On
any other platform (Windows on ARM, say) Dailies says so in the Export tab and exports numbered PNG pictures;
everything else in the mod works the same.

## FFmpeg

- **What:** the `ffmpeg` and `ffprobe` programs, used to encode exported video and sound.
- **Version:** FFmpeg 8.1.2, built for Dailies by `tools/ffmpeg/build.sh` from the release tarball, every source pinned
  by its sha256 in `tools/ffmpeg/sources.lock`. Configured without `--enable-gpl`, `--enable-nonfree` and
  `--enable-version3`, and with `--disable-autodetect`, so nothing is built in that is not named; each platform's
  exact configuration is in `META-INF/BUILDINFO-<platform>.txt` inside its encoder jar.
- **Licence:** GNU Lesser General Public License, version 2.1 or later. FFmpeg's own statement and licence texts are in
  `licenses/ffmpeg-build/ffmpeg/` (`LICENSE.md`, `COPYING.LGPLv2.1`, with the other texts FFmpeg ships beside them).
- **Where it is:** `encoder/ffmpeg-<platform>.jar` inside the Dailies jar, one per platform. On first export the
  programs for the running platform are unpacked to `<game>/dailies/encoder/<platform>/`.
- **Replacing it:** Dailies runs the encoder as a separate program and does not link against it. Any FFmpeg build can
  be used in its place by replacing the unpacked files in that folder, which is what the LGPL asks to be possible.
- **Source:** https://ffmpeg.org/, and this repository's `tools/ffmpeg/` for how it is built (see "Rebuilding").

## Libraries built into that FFmpeg

Each is under the licence named. Its own licence files, copyright notices included, are in
`licenses/ffmpeg-build/<name>/`, taken from the source it was built from, with a short notice for each in
`licenses/ffmpeg-build/notices/`.

| Library | Version | Used for | Licence |
|---|---|---|---|
| SVT-AV1 (Alliance for Open Media) | 3.0.2 | AV1 video, software | BSD 3-Clause Clear, with the Alliance for Open Media Patent License 1.0 |
| libaom (Alliance for Open Media) | 3.12.1 | AV1 video, software | BSD 2-Clause, with the Alliance for Open Media Patent License 1.0 |
| libvpx (The WebM Project) | 1.15.2 | VP9 video | BSD 3-Clause, with Google's additional patent grant |
| Opus (Xiph.Org and others) | 1.5.2 | Opus sound | BSD 3-Clause |
| zlib | 1.3.1 | Compression inside file formats | zlib licence |
| NVIDIA codec headers (nv-codec-headers) | 13.0.19.0 | NVIDIA hardware encoding (Windows, Linux) | MIT |
| AMD AMF headers | 1.4.36 | AMD hardware encoding (Windows, Linux x86_64) | MIT |
| Intel oneVPL dispatcher (libvpl) | 2.15.0 | Intel Quick Sync hardware encoding (Windows, Linux x86_64) | MIT |
| libva | 2.22.0 | VA-API hardware encoding (Linux), shipped beside `ffmpeg` as shared libraries | MIT |

On Windows the C and C++ runtime of the MinGW compiler is linked in: the GCC runtime libraries, under the GNU General
Public License, version 3 or later, with the GCC Runtime Library Exception 3.1 (`licenses/gcc-COPYING.RUNTIME`; the GPL
text is `licenses/ffmpeg-build/ffmpeg/COPYING.GPLv3`), and mingw-w64's winpthreads, under the MIT and BSD 3-Clause
licences (`licenses/winpthreads-COPYING`).

The hardware encoders themselves (NVIDIA, AMD, Intel, Windows Media Foundation, Apple VideoToolbox and the Linux VA-API
drivers) are part of the graphics driver or of the operating system; Dailies carries only the open headers and
dispatchers needed to call them.

## H.264 and H.265

Dailies makes H.264 and H.265 only through hardware encoders. It carries no software encoder for either: x264 and
x265 would need a GPL build, and Cisco's patent licence for OpenH264 covers only binaries fetched from Cisco, so none
of the three is built in. A computer without a hardware H.264 encoder is offered AV1, VP9, or an editing master
(ProRes or DNxHR).

## What Dailies offers

Which codecs are offered is read from this build when it runs, and every hardware encoder is tried once on the
machine before it is listed. Nothing is offered that the build does not contain or the machine cannot do.

## Rebuilding

The LGPL asks that anyone can rebuild the encoder. Everything needed is kept with this source: `tools/ffmpeg/build.sh`,
with every source pinned by its sha256 in `tools/ffmpeg/sources.lock`, and the workflow `.github/workflows/ffmpeg.yml`,
which builds, checks and packs every platform on GitHub's machines. `./tools/ffmpeg/build.sh build <platform>` then
`pack` does the same on a machine of your own. Dailies can also be built with the JavaCPP Presets' Windows-only build
(`-Pdailies.encoderSource=bytedeco`), whose licence texts then go into the jar's `licenses` folder instead.

## YAMNet

- **What:** the trained weights of YAMNet, Google's sound event classifier, which Moments uses to name a burst of
  noise in voice chat as laughter, cheering, screaming or shouting (settings `momentsAiEnabled` and `momentsBurstLabels`,
  both needed). It runs inside Dailies, in Java, on the player's own computer; no audio leaves it.
- **Source:** `yamnet.h5` from https://storage.googleapis.com/audioset/yamnet.h5, as linked from
  https://github.com/tensorflow/models/tree/master/research/audioset/yamnet
- **Changed:** yes. The weights were converted to Dailies' own file, `assets/dailies/models/yamnet.bin`, with batch
  normalisation folded into the convolution weights and the kernels stored as 16-bit floats. The network and its
  sound front end were written afresh in Java from Google's published description (`yamnet.py`, `features.py`,
  `params.py`).
- **Licence:** Apache License 2.0, Copyright Google LLC and the TensorFlow Authors. The text is
  `licenses/YAMNet-LICENSE`, as the `tensorflow/models` repository publishes it.

## AudioSet ontology

- **What:** the display names of the 521 AudioSet classes YAMNet scores, `assets/dailies/models/yamnet_classes.txt`,
  unchanged from `yamnet_class_map.csv` in the repository above.
- **Credit:** AudioSet ontology by Google (https://research.google.com/audioset/), licensed under
  CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). The notice is `licenses/AudioSet-ontology-CC-BY-4.0.txt`.
