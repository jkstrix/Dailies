# Third-party notices

Dailies itself is under the MIT licence (see `LICENSE`). Its jar also carries the video encoder below, unchanged, as
a separate program, and the weights of a small sound classifier (see "YAMNet" at the end). This file and the licence texts it names are inside the jar too: this file at the top, the texts
in the `licenses` folder.

## Platforms

A video encoder is packed in for **Windows on 64-bit Intel or AMD processors (windows-x86_64)** only, for now.
On Linux and macOS, and on Windows on ARM, Dailies says so in the Export tab and exports numbered PNG pictures;
everything else in the mod works the same. Encoders for the other platforms are to come from a build of our own
(see "Rebuilding" below).

## FFmpeg

- **What:** the FFmpeg libraries and the `ffmpeg` and `ffprobe` programs, used to encode exported video and sound.
- **Version:** FFmpeg 8.1.2, as packaged by the JavaCPP Presets, artifact `org.bytedeco:ffmpeg:8.1.2-1.5.14`,
  platform `windows-x86_64`. This is the build without the `-gpl` classifier: it is configured with
  `--enable-version3` and without `--enable-gpl` or `--enable-nonfree`.
- **Licence:** GNU Lesser General Public License, version 3 or later (FFmpeg is LGPL 2.1 or later; `--enable-version3`
  makes this build LGPL 3 or later). FFmpeg's own statement is `licenses/FFmpeg-LICENSE.md`; the licence is
  `licenses/FFmpeg-COPYING.LGPLv3`, which adds to the GNU General Public License, version 3,
  `licenses/FFmpeg-COPYING.GPLv3`.
- **Where it is:** `encoder/ffmpeg-windows-x86_64.jar` inside the Dailies jar. On first export its files are unpacked
  to `<game>/dailies/encoder/windows-x86_64/`.
- **Replacing it:** Dailies runs the encoder as a separate program and does not link against it. Any FFmpeg build can
  be used in its place by replacing the unpacked files in that folder, which is what the LGPL asks to be possible.
- **Source:** https://ffmpeg.org/ and https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg (the build
  script `cppbuild.sh` there names the version of every library below and fetches its source).

## Libraries built into that FFmpeg

The list is taken from the build's own configuration line (`ffmpeg -version`) and from the JavaCPP Presets' build
script. Each library is under the licence named. The text in the `licenses` folder is that project's own licence
file, copyright notice included, as its official repository publishes it.

| Library | Used for | Licence | Text in `licenses/` |
|---|---|---|---|
| SVT-AV1 (Alliance for Open Media) | AV1 video, software | BSD 3-Clause Clear, with the Alliance for Open Media Patent License 1.0 | `SVT-AV1-LICENSE.md`, `SVT-AV1-PATENTS.md` |
| libaom (Alliance for Open Media) | AV1 video, software | BSD 2-Clause, with the Alliance for Open Media Patent License 1.0 | `libaom-LICENSE`, `libaom-PATENTS` |
| libvpx (The WebM Project) | VP8 and VP9 video | BSD 3-Clause, with Google's additional patent grant | `libvpx-LICENSE`, `libvpx-PATENTS` |
| Opus (Xiph.Org and others) | Opus sound | BSD 3-Clause | `opus-COPYING` |
| OpenH264 (Cisco Systems) | Not used by Dailies, see below | BSD 2-Clause | `openh264-LICENSE` |
| LAME (libmp3lame) | Not used by Dailies | GNU Library General Public License, version 2 or later | `lame-COPYING` |
| Speex (Xiph.Org and others) | Not used by Dailies | BSD 3-Clause | `speex-COPYING` |
| OpenCORE AMR | Not used by Dailies | Apache License 2.0 | `opencore-amr-LICENSE` |
| VisualOn AMR-WB encoder | Not used by Dailies | Apache License 2.0 | `vo-amrwbenc-COPYING` |
| OpenSSL | Not used by Dailies (network protocols) | Apache License 2.0 | `openssl-LICENSE.txt` |
| SRT (Haivision) | Not used by Dailies (network protocols) | Mozilla Public License 2.0 | `srt-LICENSE` |
| FreeType | Not used by Dailies (text drawing) | FreeType License (the project offers that or GPL 2; the FreeType License is taken) | `freetype-LICENSE.TXT`, `freetype-FTL.TXT` |
| HarfBuzz | Not used by Dailies (text drawing) | "Old MIT" | `harfbuzz-COPYING` |
| libxml2 | Not used by Dailies | MIT | `libxml2-Copyright` |
| libwebp (Google) | Not used by Dailies | BSD 3-Clause, with Google's additional patent grant | `libwebp-COPYING`, `libwebp-PATENTS` |
| zimg | Not used by Dailies | WTFPL, version 2 | `zimg-COPYING` |
| zlib | Compression inside file formats | zlib licence | `zlib-LICENSE` |
| Intel Media SDK dispatcher (mfx_dispatch) | Intel Quick Sync hardware encoding | BSD 3-Clause | `mfx_dispatch-LICENSE` |
| NVIDIA codec headers (nv-codec-headers) | NVIDIA hardware encoding | MIT | `nv-codec-headers-LICENSE.txt` |
| mingw-w64 winpthreads (`libwinpthread-1.dll`) | Threads on Windows | MIT and BSD 3-Clause | `winpthreads-COPYING` |
| GCC runtime libraries, linked in | The C and C++ runtime | GNU General Public License, version 3 or later, with the GCC Runtime Library Exception 3.1 | `FFmpeg-COPYING.GPLv3`, `gcc-COPYING.RUNTIME` |
| JavaCPP Presets glue (`jni*.dll`, Bytedeco) | Not used by Dailies; unpacked with the rest | Apache License 2.0 (or GPL 2 with the Classpath exception; Apache is taken) | `javacpp-presets-LICENSE.txt` |

FreeType asks for this acknowledgement: portions of this software are copyright The FreeType Project
(https://freetype.org). All rights reserved.

The licence files were taken from each project's official repository in November 2026, from its main branch. The
versions built into the packaged FFmpeg may be a little older, and their copyright years with them.

The hardware encoders (NVIDIA, Intel, and Windows Media Foundation) are part of the graphics driver or of Windows;
Dailies carries only the open headers needed to call them.

## H.264 and H.265

Dailies makes H.264 and H.265 only through hardware encoders. It carries no software encoder for either: x264 and
x265 would need a GPL build, and Cisco's patent licence for OpenH264 covers only binaries fetched from Cisco. The
packaged FFmpeg above happens to contain OpenH264; **Dailies never uses it**, neither by default nor when asked for it
by name. A computer without a hardware H.264 encoder is offered AV1, VP9, or an editing master (ProRes or DNxHR).

## What Dailies offers

Which codecs are offered is read from this build when it runs, and every hardware encoder is tried once on the
machine before it is listed. Nothing is offered that the build does not contain or the machine cannot do.

## Rebuilding

The LGPL asks that anyone can rebuild the encoder. Today the natives are the JavaCPP Presets' own, and their build
script is at the address above. A build script of our own, for every platform, is planned and will be kept with
this source.


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
